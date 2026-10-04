import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { api, ApiError } from '../services/api';
import { useAppTranslation, useLocale } from './LocaleProvider';
import { LOCALE_CHANGED_EVENT, type LanguagePreference } from './store';
import { responseBelongsToSession, settlePending, type LanguagePending } from './syncState';

const pendingKey = (userId: string) => `beauty:locale-pending:${userId}`;
const cacheKey = (userId: string) => `beauty:locale-account:${userId}`;
type Confirmed = { preference: LanguagePreference; revision: number };
function readConfirmed(userId: string): Confirmed | null {
  try {
    const value = JSON.parse(localStorage.getItem(cacheKey(userId)) ?? 'null');
    return value && (value.preference === 'system' || value.preference === 'en' || value.preference === 'ru') &&
      Number.isInteger(value.revision) ? value : null;
  } catch { return null; }
}
function writeConfirmed(userId: string, value: Confirmed) {
  const previous = readConfirmed(userId);
  if (!previous || value.revision >= previous.revision) localStorage.setItem(cacheKey(userId), JSON.stringify(value));
}
function confirmedRevision(userId: string, profileRevision = 0): number {
  return Math.max(profileRevision, readConfirmed(userId)?.revision ?? 0);
}
function readPending(userId: string): LanguagePending | null {
  try {
    const value = JSON.parse(localStorage.getItem(pendingKey(userId)) ?? 'null');
    return value && typeof value.id === 'string' &&
      (value.preference === 'system' || value.preference === 'en' || value.preference === 'ru') &&
      Number.isInteger(value.revision) ? value : null;
  } catch { return null; }
}
function writePending(userId: string, pending: LanguagePending | null) {
  if (pending) localStorage.setItem(pendingKey(userId), JSON.stringify(pending));
  else localStorage.removeItem(pendingKey(userId));
}

/** Reconcile on edits, sign-in, focus and reconnect; never poll. */
export function LanguageAccountSync() {
  const { user, updateUser, sessionGeneration } = useAuth();
  const { t } = useAppTranslation();
  const { applyAccountPreference, restoreGuestPreference } = useLocale();
  const [conflict, setConflict] = useState(false);
  const userRef = useRef(user);
  const generationRef = useRef(sessionGeneration);
  userRef.current = user;
  generationRef.current = sessionGeneration;
  const inFlight = useRef(new Set<string>());
  const validSession = useCallback((id: string, generation: number) =>
    responseBelongsToSession(id, generation, userRef.current?.id, generationRef.current), []);

  const syncPending = useCallback(async () => {
    const initial = userRef.current;
    const generation = generationRef.current;
    if (!initial || !readPending(initial.id)) return;
    const flightKey = `${initial.id}:${generation}`;
    if (inFlight.current.has(flightKey)) return;
    inFlight.current.add(flightKey);
    let retryNewerEdit = false;
    const write = async () => {
      const pending = readPending(initial.id);
      if (!pending || !validSession(initial.id, generation)) return;
      try {
        const saved = await api.updateLanguagePreference(pending.preference, pending.revision, initial.id);
        if (!validSession(initial.id, generation)) return;
        const latest = readPending(initial.id);
        if (!latest) return;
        const rebased = settlePending(pending, latest, saved.revision);
        writePending(initial.id, rebased);
        retryNewerEdit = !!rebased;
        writeConfirmed(initial.id, saved);
        // Update the ref synchronously: a GET can settle before React renders this PUT.
        userRef.current = { ...userRef.current!, languagePreference: saved.preference, languageRevision: saved.revision };
        updateUser(userRef.current);
      } catch (error) {
        if (!validSession(initial.id, generation)) return;
        if (error instanceof ApiError && error.status === 409) {
          const serverPreference = error.body.preference ?? error.body.languagePreference;
          const serverRevision = error.body.revision ?? error.body.languageRevision;
          if (serverPreference && typeof serverRevision === 'number') {
            const latest = readPending(initial.id);
            if (latest?.id === pending.id) writePending(initial.id, null);
            else if (latest) {
              writePending(initial.id, { ...latest, revision: serverRevision });
              retryNewerEdit = true;
            }
            userRef.current = { ...userRef.current!, languagePreference: serverPreference, languageRevision: serverRevision };
            updateUser(userRef.current);
            writeConfirmed(initial.id, { preference: serverPreference, revision: serverRevision });
            if (!retryNewerEdit) {
              applyAccountPreference(serverPreference);
              setConflict(true);
            }
          }
        }
        // Network failures retain pending until focus or reconnect.
      }
    };
    try {
      if (navigator.locks) await navigator.locks.request(`beauty:locale:${initial.id}`, { mode: 'exclusive' }, write);
      else await write();
    } finally {
      inFlight.current.delete(flightKey);
      const current = userRef.current;
      if (current && (retryNewerEdit || !validSession(initial.id, generation)) && readPending(current.id)) void syncPending();
    }
  }, [applyAccountPreference, updateUser, validSession]);

  useEffect(() => {
    if (!user) {
      restoreGuestPreference();
      setConflict(false);
      return;
    }
    const pending = readPending(user.id);
    const cached = readConfirmed(user.id);
    const profileRevision = user.languageRevision ?? 0;
    const confirmed = cached && cached.revision > profileRevision
      ? cached : { preference: user.languagePreference ?? 'system', revision: profileRevision };
    writeConfirmed(user.id, confirmed);
    applyAccountPreference(pending?.preference ?? confirmed.preference);
    void syncPending();
  // Run on auth lifecycle changes, not profile updates.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.id, sessionGeneration]);

  useEffect(() => {
    const onChange = (event: Event) => {
      const current = userRef.current;
      const next = (event as CustomEvent<LanguagePreference>).detail;
      if (!current || (next !== 'system' && next !== 'en' && next !== 'ru')) return;
      setConflict(false);
      writePending(current.id, {
        id: crypto.randomUUID?.() ?? `${Date.now()}-${Math.random()}`,
        preference: next,
        revision: confirmedRevision(current.id, current.languageRevision),
      });
      void syncPending();
    };
    window.addEventListener(LOCALE_CHANGED_EVENT, onChange);
    return () => window.removeEventListener(LOCALE_CHANGED_EVENT, onChange);
  }, [syncPending]);

  useEffect(() => {
    const reconcile = async () => {
      const current = userRef.current;
      const generation = generationRef.current;
      if (!current) return;
      const pending = readPending(current.id);
      if (pending) { applyAccountPreference(pending.preference); void syncPending(); return; }
      try {
        const profile = await api.getCurrentUser();
        if (validSession(current.id, generation) && !readPending(current.id) && profile.id === current.id &&
            (profile.languageRevision ?? 0) >= confirmedRevision(current.id, userRef.current?.languageRevision)) {
          writeConfirmed(current.id, { preference: profile.languagePreference ?? 'system', revision: profile.languageRevision ?? 0 });
          userRef.current = profile;
          updateUser(profile);
          applyAccountPreference(profile.languagePreference ?? 'system');
        }
      } catch { /* Ordinary auth/offline handling applies. */ }
    };
    const onStorage = (event: StorageEvent) => {
      const current = userRef.current;
      if (current && event.key === pendingKey(current.id)) void reconcile();
    };
    window.addEventListener('focus', reconcile);
    window.addEventListener('online', reconcile);
    window.addEventListener('storage', onStorage);
    return () => {
      window.removeEventListener('focus', reconcile);
      window.removeEventListener('online', reconcile);
      window.removeEventListener('storage', onStorage);
    };
  }, [applyAccountPreference, syncPending, updateUser, validSession]);

  return conflict ? <div role="status" className="locale-conflict-notice">{t('language.conflict')} <button type="button" aria-label={t('common.close')} onClick={() => setConflict(false)}>×</button></div> : null;
}
