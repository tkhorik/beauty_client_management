import { useAppTranslation, useLocale } from '../i18n/useLocale';
import React, { useEffect, useState } from 'react';
import { Building2, LogOut, Plus, UserPlus, Clock, Link2Off, ShieldCheck } from 'lucide-react';
import { api, ApiError, writeErrorMessage } from '../services/api';
import { translatedFieldErrors } from '../i18n/errors';
import { useAuth } from '../auth/useAuth';
import { useOrg } from '../auth/useOrg';
import { stripQueryString } from '../auth/route';
import { pendingJoinSlug } from '../auth/deepLinks';
import type { Organization } from '../types';
import { ThemeSelector } from './ThemeSelector';
import { LanguageSelector } from './LanguageSelector';
import './AuthScreens.css';

/**
 * Captured once, at module-evaluation time — not inside the component.
 *
 * A `useState(() => readCreationTokenFromUrl())` initializer looked
 * equivalent but is not: React 18 StrictMode mounts every component twice in
 * development, discarding the first instance after running (and cleaning up)
 * its effects. This component's effect strips the query string as its first
 * step, so the *discarded* mount's effect would strip it before the
 * *surviving* mount's own lazy initializer got a chance to read it, leaving
 * the real instance with `null` every time. Reading the URL once when this
 * module is first evaluated — before React has mounted anything at all —
 * means both StrictMode instances see the same captured value regardless of
 * how many times the component itself mounts.
 */
const initialCreationToken = new URLSearchParams(window.location.search).get('orgToken');

type TokenStatus = 'checking' | 'valid' | 'invalid';

/**
 * Shown when the signed-in user belongs to no organization.
 *
 * This screen is load-bearing rather than cosmetic. Clients and visits belong
 * to an organization, so with none selected there is literally nothing for the
 * app to show — and the backend answers every data request with
 * `MISSING_ORGANIZATION` until one is chosen. Both routes out are offered
 * because both are legitimate: someone opening the product for their own salon
 * creates one, and someone hired by a salon that already uses it joins.
 *
 * Pending requests are listed here too. Without that, a user who has already
 * asked to join sees an unchanged empty screen, asks again, and gets a unique
 * constraint error they cannot interpret.
 */
interface OrganizationOnboardingProps {
  /**
   * Opens the global admin panel. The only entry point available to a
   * SUPER_ADMIN who belongs to no organization yet — see `App.tsx` for why
   * this is threaded down here rather than only living in `Header`.
   */
  onOpenAdmin: () => void;
  onCreated?: () => void;
  onCancel?: () => void;
}

export const OrganizationOnboarding: React.FC<OrganizationOnboardingProps> = ({ onOpenAdmin, onCreated, onCancel }) => {
  const { t } = useAppTranslation();
  const { locale } = useLocale();
  const { logout, user } = useAuth();
  const { organizations, refresh } = useOrg();

  // The module-level capture (see [initialCreationToken]) is the source of
  // truth; this is just where the component keeps its own copy.
  const [creationToken] = useState<string | null>(initialCreationToken);
  const [tokenStatus, setTokenStatus] = useState<TokenStatus>(creationToken ? 'checking' : 'invalid');

  useEffect(() => {
    if (!creationToken) return;
    stripQueryString();

    let cancelled = false;
    api.validateCreationToken(creationToken)
      .then(valid => { if (!cancelled) setTokenStatus(valid ? 'valid' : 'invalid'); })
      // A network failure is not proof the link is bad, but there is nothing
      // useful to do with an unverifiable token either — the create form
      // stays hidden until the check can actually run.
      .catch(() => { if (!cancelled) setTokenStatus('invalid'); });
    return () => { cancelled = true; };
  }, [creationToken]);

  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [createErrors, setCreateErrors] = useState<Record<string, string>>({});
  const [creating, setCreating] = useState(false);

  // Pre-filled from an admin's `?join=` link, unless sign-up already filed it.
  const [joinSlug, setJoinSlug] = useState(() => pendingJoinSlug() ?? '');
  const [joinError, setJoinError] = useState('');
  const [joinNotice, setJoinNotice] = useState('');
  const [joining, setJoining] = useState(false);

  const pending = organizations.filter(o => o.status === 'PENDING' || o.status === 'INVITED');
  const declined = organizations.filter(o => o.status === 'DECLINED');

  function formatDate(iso: string): string {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
  }

  function declinedMessage(o: Organization): string {
    const canRetry = !o.retryAfter || new Date(o.retryAfter) <= new Date();
    return canRetry
      ? t('organizationOnboarding.declinedCanRetry', { name: o.name })
      : t('organizationOnboarding.declinedUntil', { name: o.name, date: formatDate(o.retryAfter!) });
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    if (!creationToken) return;
    setCreateErrors({});
    setCreating(true);
    try {
      await api.createOrganization(name.trim(), slug.trim() || undefined, creationToken);
      // `refresh` re-reads the list and selects the new organization, which
      // unmounts this screen.
      await refresh();
      onCreated?.();
    } catch (err) {
      if (err instanceof ApiError && err.body.fieldErrors) setCreateErrors(translatedFieldErrors(err.body));
      else if (err instanceof ApiError) setCreateErrors({ name: writeErrorMessage(err, t('organizationOnboarding.couldNotCreateTheOrganizationPleaseTryAgain')) });
      else setCreateErrors({ name:t('organizationOnboarding.couldNotCreateTheOrganizationPleaseTryAgain') });
    } finally {
      setCreating(false);
    }
  }

  async function handleJoin(e: React.FormEvent) {
    e.preventDefault();
    setJoinError('');
    setJoinNotice('');
    setJoining(true);
    try {
      const result = await api.requestToJoinOrganization(joinSlug.trim().toLowerCase());
      await refresh();
      // ACTIVE means they had a standing invitation and this was the
      // acceptance; PENDING means an administrator still has to approve.
      setJoinNotice(
        result.status === 'ACTIVE'
          ? t('organizationOnboarding.joined', { name: result.name })
          : t('organizationOnboarding.requestSent', { name: result.name })
      );
      setJoinSlug('');
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) setJoinError(t('organizationOnboarding.noOrganizationWithThatHandle'));
      else if (err instanceof ApiError && err.body.code === 'REQUEST_DECLINED' && err.body.retryAfter) {
        setJoinError(t('organizationOnboarding.declinedRetryAfter', { date: formatDate(err.body.retryAfter) }));
      }
      else if (err instanceof ApiError) setJoinError(writeErrorMessage(err, t('organizationOnboarding.couldNotSendTheRequestPleaseTryAgain')));
      else setJoinError(t('organizationOnboarding.couldNotSendTheRequestPleaseTryAgain'));
    } finally {
      setJoining(false);
    }
  }

  return (
    <main className="onboarding-screen">
      <div className="onboarding-card">
        <div className="onboarding-top"><span aria-hidden="true" /><div className="display-preferences"><ThemeSelector compact /><LanguageSelector compact /></div></div>
        {onCancel && (
          <button type="button" className="btn-secondary" onClick={onCancel} style={{ marginBottom: '16px' }}>
            {t('common.cancel')}
          </button>
        )}

        <div className="onboarding-heading">
          <Building2 size={24} />
          <h1>{t('organizationOnboarding.chooseAnOrganization')}</h1>
        </div>
        <p className="onboarding-intro">
          {t('organizationOnboarding.clientsAndVisitsBelongToAnOrganizationJoinOneThatAlreadyExistsOr')}
        </p>

        {pending.length > 0 && (
          <div className="auth-alert auth-alert--info" style={{ marginBottom: '24px', display: 'flex', gap: '8px', alignItems: 'flex-start' }}>
            <Clock size={16} style={{ flexShrink: 0, marginTop: '1px' }} />
            <div>
              {t('organizationOnboarding.waitingOnApproval')}{' '}
              {pending.map(o => o.name).join(', ')}
            </div>
          </div>
        )}

        {declined.map(o => (
          <div key={o.id} className="auth-alert auth-alert--error" style={{ marginBottom: '16px' }}>
            {declinedMessage(o)}
          </div>
        ))}

        {/*
          Create — gated on a valid organization-creation link. Organization
          creation is no longer self-service (see AdminRoutes.kt /
          OrgCreationTokenService on the backend): without `?orgToken=…` in
          the URL this whole section is replaced by an explanatory message,
          and the backend would reject the request regardless even if this
          check were somehow bypassed client-side.
        */}
        {tokenStatus === 'valid' && (
          <section className="onboarding-section"><form onSubmit={handleCreate}>
            <h2>
              <Plus size={16} /> {t('organizationOnboarding.createANewOrganization')}
            </h2>
            <div className="onboarding-field">
              <label htmlFor="organization-name">
                {t('organizationOnboarding.name')}
              </label>
              <input
                className="input-field"
                id="organization-name"
                value={name}
                onChange={e => setName(e.target.value)}
                placeholder="Aura Downtown"
                aria-invalid={!!createErrors.name}
              />
              {createErrors.name && <div className="auth-field-error">{createErrors.name}</div>}
            </div>
            <div className="onboarding-field">
              <label htmlFor="organization-slug">
                {t('organizationOnboarding.handleOptional')}
              </label>
              <input
                className="input-field"
                id="organization-slug"
                value={slug}
                onChange={e => setSlug(e.target.value)}
                placeholder="aura-downtown"
                aria-invalid={!!createErrors.slug}
              />
              <div className="auth-hint">
                {t('organizationOnboarding.thisIsWhatColleaguesTypeToRequestAccessLowercaseLettersNumbersAn')}
              </div>
              {createErrors.slug && <div className="auth-field-error">{createErrors.slug}</div>}
            </div>
            <button type="submit" className="btn-rose" disabled={creating || !name.trim()}>
              {creating ?t('organizationOnboarding.creating') :t('organizationOnboarding.createOrganization')}
            </button>
          </form></section>
        )}

        {tokenStatus === 'checking' && (
          <div className="auth-alert auth-alert--info" style={{ marginBottom: '32px' }}>{t('organizationOnboarding.checkingYourInvitationLink')}</div>
        )}

        {tokenStatus === 'invalid' && (
          <section className="onboarding-section">
            <h2>
              <Link2Off size={16} /> {t('organizationOnboarding.createANewOrganization')}
            </h2>
            <div className={`auth-alert ${creationToken ? 'auth-alert--error' : 'auth-alert--info'}`}>
              {creationToken
                ?t('organizationOnboarding.thisInvitationLinkIsInvalidExpiredOrHasAlreadyBeenUsedAskYourAdm')
                :t('organizationOnboarding.creatingANewOrganizationRequiresAnInvitationLinkFromAnAdministra')}
            </div>
          </section>
        )}

        {/* Join */}
        <section className="onboarding-section"><form onSubmit={handleJoin}>
          <h2>
            <UserPlus size={16} /> {t('organizationOnboarding.joinAnExistingOne')}
          </h2>
          <div className="onboarding-field">
            <label htmlFor="join-organization-slug">
              {t('organizationOnboarding.organizationHandle')}
            </label>
            <input
              className="input-field"
              id="join-organization-slug"
              value={joinSlug}
              onChange={e => setJoinSlug(e.target.value)}
              placeholder="aura-downtown"
              aria-invalid={!!joinError}
            />
            {joinError && <div className="auth-field-error">{joinError}</div>}
          </div>
          {joinNotice && <div className="auth-alert auth-alert--success">{joinNotice}</div>}
          <button type="submit" className="btn-rose" disabled={joining || !joinSlug.trim()}>
            {joining ?t('organizationOnboarding.sending') :t('organizationOnboarding.requestAccess')}
          </button>
        </form></section>

        <div className="onboarding-footer">
          <button
            onClick={logout}
            className="auth-quiet-button"
          >
            <LogOut size={14} /> {t('organizationOnboarding.signOut')}
          </button>

          {/*
            The only way a SUPER_ADMIN with no organization yet — a freshly
            bootstrapped account, most likely — can reach the admin panel to
            issue their first creation link.
          */}
          {user?.globalRole === 'SUPER_ADMIN' && (
            <button
              onClick={onOpenAdmin}
              className="auth-quiet-button"
            >
              <ShieldCheck size={14} /> {t('organizationOnboarding.adminPanel')}
            </button>
          )}
        </div>
      </div>
    </main>
  );
};
