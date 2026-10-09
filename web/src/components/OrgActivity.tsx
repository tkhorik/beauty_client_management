import React, { useCallback, useEffect, useState } from 'react';
import type { OrgAuditEvent } from '../types';
import { api, writeErrorMessage } from '../services/api';
import { useAppTranslation, useLocale } from '../i18n/LocaleProvider';
import type { TranslationKey } from '../i18n/locales';

/** Matches the backend's default page size for `GET /organizations/{id}/audit`. */
const PAGE_SIZE = 50;

const ACTION_KEYS: Record<OrgAuditEvent['action'], TranslationKey> = {
  ORG_CREATED: 'orgActivity.ORG_CREATED',
  JOIN_REQUESTED: 'orgActivity.JOIN_REQUESTED',
  APPROVED: 'orgActivity.APPROVED',
  DECLINED: 'orgActivity.DECLINED',
  INVITED: 'orgActivity.INVITED',
  INVITATION_ACCEPTED: 'orgActivity.INVITATION_ACCEPTED',
  ROLE_CHANGED: 'orgActivity.ROLE_CHANGED',
  REMOVED: 'orgActivity.REMOVED',
  REVOKED: 'orgActivity.REVOKED',
  RESTORED: 'orgActivity.RESTORED',
};

/**
 * An organization's membership history, newest first, for its admins.
 *
 * Read-only by design: the log is append-only on the server, and offering
 * anything to click here would suggest otherwise.
 */
export const OrgActivity: React.FC<{ orgId: string }> = ({ orgId }) => {
  const { t } = useAppTranslation();
  const { locale } = useLocale();
  const [events, setEvents] = useState<OrgAuditEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState('');

  const loadPage = useCallback(async (before?: string) => {
    setLoading(true);
    setError('');
    try {
      const page = await api.getOrganizationAudit(orgId, before);
      setEvents(prev => (before ? [...prev, ...page] : page));
      setHasMore(page.length === PAGE_SIZE);
    } catch (err) {
      setError(writeErrorMessage(err, t('orgActivity.couldNotLoad')));
    } finally {
      setLoading(false);
    }
  }, [orgId, t]);

  useEffect(() => { void loadPage(); }, [loadPage]);

  const unknown = t('orgActivity.unknownUser');
  const roleLabel = (detail?: string) =>
    detail === 'ORG_ADMIN' ? t('membersModal.administrator') : detail === 'ORG_USER' ? t('membersModal.member') : (detail ?? '');
  const formatter = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' });

  if (error) return <p style={{ color: '#e87c8a', fontSize: '13px' }}>{error}</p>;
  if (!loading && events.length === 0) {
    return <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>{t('orgActivity.empty')}</p>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
      <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: '8px' }}>
        {events.map(event => (
          <li
            key={event.id}
            style={{ padding: '10px 14px', borderRadius: '10px', border: '1px solid var(--border-color)', fontSize: '13px' }}
          >
            <div>
              {t(ACTION_KEYS[event.action], {
                actor: event.actorName ?? unknown,
                target: event.targetName ?? unknown,
                role: roleLabel(event.detail),
              })}
            </div>
            <time dateTime={event.createdAt} style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
              {formatter.format(new Date(event.createdAt))}
            </time>
          </li>
        ))}
      </ul>
      {loading && <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>{t('common.loading')}</p>}
      {!loading && hasMore && (
        <button
          type="button"
          className="btn-secondary"
          onClick={() => void loadPage(events[events.length - 1]?.createdAt)}
        >
          {t('orgActivity.loadMore')}
        </button>
      )}
    </div>
  );
};
