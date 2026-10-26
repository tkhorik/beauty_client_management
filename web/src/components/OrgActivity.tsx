import React, { useCallback, useEffect, useState } from 'react';
import type { OrgAuditEvent } from '../types';
import { api, writeErrorMessage } from '../services/api';
import { useAppTranslation, useLocale } from '../i18n/useLocale';
import type { TranslationKey } from '../i18n/locales';
import './Administration.css';

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
  INVITE_LINK_CREATED: 'orgActivity.INVITE_LINK_CREATED',
  INVITE_LINK_REVOKED: 'orgActivity.INVITE_LINK_REVOKED',
  INVITE_LINK_ACCEPTED: 'orgActivity.INVITE_LINK_ACCEPTED',
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

  if (error) return <p className="notice notice--error" role="alert">{error}</p>;
  if (!loading && events.length === 0) {
    return <p className="administration-row__meta">{t('orgActivity.empty')}</p>;
  }

  return (
    <div className="org-activity">
      <ul className="org-activity__list">
        {events.map(event => (
          <li
            key={event.id}
            className="org-activity__event"
          >
            <div>
              {t(ACTION_KEYS[event.action], {
                actor: event.actorName ?? unknown,
                target: event.targetName ?? unknown,
                role: roleLabel(event.detail),
              })}
            </div>
            <time dateTime={event.createdAt}>
              {formatter.format(new Date(event.createdAt))}
            </time>
          </li>
        ))}
      </ul>
      {loading && <p className="administration-row__meta">{t('common.loading')}</p>}
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
