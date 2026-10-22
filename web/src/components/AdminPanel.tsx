import { useAppTranslation, useLocale } from '../i18n/LocaleProvider';
import React, { useCallback, useEffect, useState } from 'react';
import { useDismiss } from './useDismiss';
import { X, ShieldCheck, Users, Building2, Link2, Copy, Check, Trash2, Ban, RotateCcw, UserCog } from 'lucide-react';
import type { AdminUser, AdminOrganization, OrganizationCreationLink } from '../types';
import { api, writeErrorMessage } from '../services/api';
import { MembersModal } from './MembersModal';
import { useAuth } from '../auth/AuthContext';
import { useOrg } from '../auth/OrgContext';
import './Administration.css';

interface AdminPanelProps {
  onClose: () => void;
}

type Tab = 'users' | 'organizations' | 'links';

/**
 * Global, cross-organization administration for a `SUPER_ADMIN`.
 *
 * Deliberately reachable independent of whether the signed-in super admin
 * currently belongs to any organization — see `App.tsx`, which renders this
 * as a sibling of `OrganizationGate` rather than nesting it inside
 * `AuthenticatedApp`. A freshly bootstrapped super admin (`SUPER_ADMIN_EMAILS`
 * at startup) may have zero organizations, and the only way for them to hand
 * out the first creation link is to reach this screen without one.
 *
 * Every action here is a convenience, not the control: the backend's
 * `requireSuperAdmin()` guard is what actually enforces all of this, so a
 * client-side bug here can make the UI wrong but never insecure.
 */
export const AdminPanel: React.FC<AdminPanelProps> = ({ onClose }) => {
  const { t } = useAppTranslation();
  const dismiss = useDismiss(onClose);
  const { user } = useAuth();
  const { select, refresh: refreshOrganizations } = useOrg();
  const [tab, setTab] = useState<Tab>('users');

  const [users, setUsers] = useState<AdminUser[]>([]);
  const [orgs, setOrgs] = useState<AdminOrganization[]>([]);
  const [links, setLinks] = useState<OrganizationCreationLink[]>([]);
  // Distinct from a plain `loading` boolean: only the very first load should
  // replace the tab content with a placeholder. Every reload *after* an
  // action (suspend, revoke, issue) re-uses this same `loadAll`, and if it
  // also flipped a shared `loading` flag the tab content — including
  // LinksTab's own `freshLink` state, below — would unmount and remount on
  // every single action, losing anything the child was holding onto.
  const [initialLoading, setInitialLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  // Lifted out of LinksTab rather than kept as its local state, for the same
  // reason: a raw creation-link token is recoverable only in the instant it
  // is issued, and it must survive whatever re-render `loadAll` triggers
  // right after — including, in the past, an unmount this component no
  // longer performs but might again if that loading logic changes.
  const [freshLink, setFreshLink] = useState<{ url: string; token: string } | null>(null);

  /**
   * The organization whose roster is open on top of this panel, if any.
   *
   * A super admin is an administrator of every organization as far as the
   * backend is concerned — `requireOrgAccess()` grants them ORG_ADMIN for any
   * `X-Org-Id` — but until this existed there was no way to reach that: the
   * header's organization switcher only lists organizations you are a member
   * of, so a salon you had never joined was unreachable from the UI no matter
   * what the server would have allowed.
   */
  const [managingOrg, setManagingOrg] = useState<AdminOrganization | null>(null);

  const loadAll = useCallback(async () => {
    setError('');
    try {
      const [u, o, l] = await Promise.all([
        api.getAdminUsers(),
        api.getAdminOrganizations(),
        api.getCreationLinks(),
      ]);
      setUsers(u);
      setOrgs(o);
      setLinks(l);
    } catch (err) {
      setError(writeErrorMessage(err, t('adminPanel.couldNotLoadAdminData')));
    } finally {
      setInitialLoading(false);
    }
  }, [t]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  async function run(action: () => Promise<void>, success: string) {
    setError('');
    setNotice('');
    try {
      await action();
      setNotice(success);
      await loadAll();
    } catch (err) {
      setError(writeErrorMessage(err, t('adminPanel.thatActionFailed')));
    }
  }

  return (
    <div
      className="dialog-overlay administration-overlay"
      {...dismiss}
    >
      <div
        className="dialog-panel administration-panel"
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="admin-panel-title"
        tabIndex={-1}
      >
        <div className="dialog-header administration-header">
          <h2 id="admin-panel-title" className="administration-title">
            <ShieldCheck size={20} /> {t('adminPanel.adminPanel')}
          </h2>
          <button type="button" className="icon-button" aria-label={t('common.close')} onClick={onClose}>
            <X size={22} />
          </button>
        </div>

        <nav className="administration-nav" aria-label={t('adminPanel.adminPanel')}>
          <TabButton active={tab === 'users'} onClick={() => setTab('users')} icon={<Users size={15} />} label={t('adminPanel.usersTab', { count: users.length })} />
          <TabButton active={tab === 'organizations'} onClick={() => setTab('organizations')} icon={<Building2 size={15} />} label={t('adminPanel.organizationsTab', { count: orgs.length })} />
          <TabButton active={tab === 'links'} onClick={() => setTab('links')} icon={<Link2 size={15} />} label={t('adminPanel.linksTab', { count: links.length })} />
        </nav>

        <div className="dialog-body administration-body">
          {error && <div className="notice notice--error" role="alert">{error}</div>}
          {notice && <div className="notice" role="status">{notice}</div>}

          {initialLoading ? (
            <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>{t('adminPanel.loading')}</p>
          ) : tab === 'users' ? (
            <UsersTab users={users} selfId={user?.id} onRun={run} />
          ) : tab === 'organizations' ? (
            <OrganizationsTab
              orgs={orgs}
              onManageMembers={setManagingOrg}
              onOpen={org => {
                select(org.id);
                onClose();
              }}
              onArchive={(org, confirmationSlug) => run(async () => {
                await api.archiveOrganization(org.id, confirmationSlug);
                await refreshOrganizations();
              }, t('adminPanel.organizationArchived'))}
            />
          ) : (
            <LinksTab links={links} onRun={run} freshLink={freshLink} onFreshLink={setFreshLink} />
          )}
        </div>
      </div>

      {/*
        Rendered as a sibling of the panel body rather than inside the tab, so
        closing the roster returns to the admin panel with its tab, scroll
        position and freshly issued link intact. `loadAll` is re-run on close
        because a membership change moves the member counts this panel shows.
      */}
      {managingOrg && (
        // Keeps the members modal's clicks from reaching this panel's
        // backdrop handlers (they only act on a click aimed at the backdrop
        // itself, so this is belt and braces).
        <div onClick={e => e.stopPropagation()}>
          <MembersModal
            orgId={managingOrg.id}
            orgName={managingOrg.name}
            onClose={() => {
              setManagingOrg(null);
              void loadAll();
            }}
          />
        </div>
      )}
    </div>
  );
};

const TabButton: React.FC<{ active: boolean; onClick: () => void; icon: React.ReactNode; label: string }> = ({
  active,
  onClick,
  icon,
  label,
}) => (
  <button
    onClick={onClick}
    type="button"
    className="administration-nav__button"
    aria-pressed={active}
  >
    {icon} {label}
  </button>
);

// ---------------------------------------------------------------------------
// Users
// ---------------------------------------------------------------------------

const UsersTab: React.FC<{
  users: AdminUser[];
  selfId: string | undefined;
  onRun: (action: () => Promise<void>, success: string) => Promise<void>;
}> = ({ users, selfId, onRun }) => {
  const { t } = useAppTranslation();
  return (
  <div className="administration-list">
    {users.map(u => (
      <div
        key={u.id}
        className="administration-row"
      >
        <div className="administration-row__identity">
          <div className="administration-row__name">
            {u.fullName}
            {u.globalRole === 'SUPER_ADMIN' && (
              <span className="administration-badge">
                {t('adminPanel.sUPERADMIN')}
              </span>
            )}
            {u.suspendedAt && (
              <span className="administration-badge administration-badge--danger">
                {t('adminPanel.sUSPENDED')}
              </span>
            )}
          </div>
          <div className="administration-row__meta">
            {u.email} · {t('counts.organizations', { count: u.organizationCount })}
            {!u.emailVerified && t('adminPanel.unverified')}
          </div>
        </div>
        <div className="administration-actions">
          {u.id === selfId ? (
            <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>{t('adminPanel.you')}</span>
          ) : u.suspendedAt ? (
            <button
              className="btn-secondary administration-action"
              onClick={() => onRun(() => api.setUserSuspended(u.id, false), t('adminPanel.userUnsuspended', { name: u.fullName }))}
            >
              <RotateCcw size={13} /> {t('adminPanel.unsuspend')}
            </button>
          ) : (
            <button
              className="btn-secondary administration-action administration-action--danger"
              onClick={() => onRun(() => api.setUserSuspended(u.id, true), t('adminPanel.userSuspended', { name: u.fullName }))}
            >
              <Ban size={13} /> {t('adminPanel.suspend')}
            </button>
          )}
        </div>
      </div>
    ))}
  </div>
  );
};

// ---------------------------------------------------------------------------
// Organizations
// ---------------------------------------------------------------------------

const OrganizationsTab: React.FC<{
  orgs: AdminOrganization[];
  onManageMembers: (org: AdminOrganization) => void;
  onOpen: (org: AdminOrganization) => void;
  onArchive: (org: AdminOrganization, confirmationSlug: string) => void;
}> = ({ orgs, onManageMembers, onOpen, onArchive }) => {
  const { t } = useAppTranslation();
  const [confirming, setConfirming] = useState<AdminOrganization | null>(null);
  const [confirmationSlug, setConfirmationSlug] = useState('');
  return (
  <div className="administration-list">
    {orgs.map(o => (
      <div
        key={o.id}
        className="administration-row"
      >
        <div className="administration-row__identity">
          <div className="administration-row__name">{o.name}</div>
          <div className="administration-row__meta">
            {o.slug} {t('adminPanel.createdBy')} {o.createdByEmail ?? t('adminPanel.unknown')}
            {o.archivedAt && <> · {t('adminPanel.archived')}</>}
          </div>
        </div>
        <div className="administration-actions">
          <span className="administration-row__meta">
            {t('counts.activeMembers', { count: o.memberCount })}
          </span>
          <button
            disabled={!!o.archivedAt}
            className="btn-secondary administration-action"
            onClick={() => onOpen(o)}
          >
            {t('adminPanel.openOrganization')}
          </button>
          <button
            className="btn-secondary administration-action"
            disabled={!!o.archivedAt}
            onClick={() => onManageMembers(o)}
            title={t('adminPanel.manageOrganization', { name: o.name })}
          >
            <UserCog size={13} /> {t('adminPanel.manageMembers')}
          </button>
          <button
            className="btn-secondary administration-action administration-action--danger"
            disabled={!!o.archivedAt}
            onClick={() => {
              setConfirming(o);
              setConfirmationSlug('');
            }}
          >
            <Trash2 size={13} /> {t('adminPanel.archive')}
          </button>
        </div>
      </div>
    ))}
    {confirming && (
      <section className="administration-confirm" aria-label={t('adminPanel.archiveOrganization')}>
        <strong>{t('adminPanel.archiveOrganization')}: {confirming.name}</strong>
        <p>{t('adminPanel.archiveHelp')}</p>
        <label htmlFor="archive-confirmation" className="administration-label">{t('adminPanel.archivePrompt', { slug: confirming.slug })}</label>
        <input
          className="input-field"
          id="archive-confirmation"
          value={confirmationSlug}
          onChange={event => setConfirmationSlug(event.target.value)}
          placeholder={t('adminPanel.archivePrompt', { slug: confirming.slug })}
        />
        <div className="administration-actions">
          <button
            className="btn-secondary"
            onClick={() => { setConfirming(null); setConfirmationSlug(''); }}
          >
            {t('common.cancel')}
          </button>
          <button
            className="btn-rose"
            disabled={confirmationSlug !== confirming.slug}
            onClick={() => {
              onArchive(confirming, confirmationSlug);
              setConfirming(null);
              setConfirmationSlug('');
            }}
          >
            {t('adminPanel.archiveOrganization')}
          </button>
        </div>
      </section>
    )}
    {orgs.length === 0 && <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>{t('adminPanel.noOrganizationsYet')}</p>}
  </div>
  );
};

// ---------------------------------------------------------------------------
// Creation links
// ---------------------------------------------------------------------------

const LinksTab: React.FC<{
  links: OrganizationCreationLink[];
  onRun: (action: () => Promise<void>, success: string) => Promise<void>;
  /**
   * Owned by the parent, not local state — see the note on `AdminPanel`'s own
   * `freshLink`. A raw token is recoverable only in the instant it is issued,
   * so it must not depend on this component staying mounted across whatever
   * re-render `onRun`'s reload triggers.
   */
  freshLink: { url: string; token: string } | null;
  onFreshLink: (link: { url: string; token: string } | null) => void;
}> = ({ links, onRun, freshLink, onFreshLink }) => {
  const { t } = useAppTranslation();
  const { formatDate } = useLocale();
  const [label, setLabel] = useState('');
  const [maxUses, setMaxUses] = useState(5);
  const [expiresInHours, setExpiresInHours] = useState(168);
  const [issuing, setIssuing] = useState(false);
  const [issueError, setIssueError] = useState('');
  const [copied, setCopied] = useState(false);

  async function handleIssue(e: React.FormEvent) {
    e.preventDefault();
    setIssueError('');
    setIssuing(true);
    try {
      const result = await api.createCreationLink(label.trim() || undefined, maxUses, expiresInHours);
      onFreshLink({ url: result.url, token: result.token });
      setLabel('');
      await onRun(async () => {},t('adminPanel.linkCreated'));
    } catch (err) {
      setIssueError(writeErrorMessage(err, t('adminPanel.couldNotCreateTheLink')));
    } finally {
      setIssuing(false);
    }
  }

  async function copyLink() {
    if (!freshLink) return;
    await navigator.clipboard.writeText(freshLink.url);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <div className="administration-stack">
      <form onSubmit={handleIssue} className="administration-section">
        <h3>{t('adminPanel.issueANewLink')}</h3>
        <div className="administration-form-row">
          <label className="administration-field"><span>{t('adminPanel.labelOptionalEGQ3SalonBatch')}</span>
          <input
            className="input-field"
            value={label}
            onChange={e => setLabel(e.target.value)}
            placeholder={t('adminPanel.labelOptionalEGQ3SalonBatch')}
          />
          </label><label className="administration-field">
            {t('adminPanel.uses')}
            <input aria-label={t('adminPanel.uses')}
              className="input-field"
              style={{ width: '70px' }}
              type="number"
              min={1}
              value={maxUses}
              onChange={e => setMaxUses(Math.max(1, Number(e.target.value) || 1))}
            />
          </label>
          <label className="administration-field">
            {t('adminPanel.expiresIn')}
            <select aria-label={t('adminPanel.expiresIn')}
              className="input-field"
              style={{ width: 'auto' }}
              value={expiresInHours}
              onChange={e => setExpiresInHours(Number(e.target.value))}
            >
              <option value={24}>{t('adminPanel.1Day')}</option>
              <option value={168}>{t('adminPanel.7Days')}</option>
              <option value={720}>{t('adminPanel.30Days')}</option>
              <option value={8760}>{t('adminPanel.1Year')}</option>
            </select>
          </label>
          <button type="submit" className="btn-rose" disabled={issuing}>
            {issuing ?t('adminPanel.creating') :t('adminPanel.createLink')}
          </button>
        </div>
        {issueError && <div className="notice notice--error" role="alert">{issueError}</div>}
      </form>

      {freshLink && (
        <div className="notice administration-fresh-link">
          <div>{t('adminPanel.thisLinkIsShownOnceCopyItNowAndHandItToWhoeverShouldCreateTheOrg')}</div>
          <div className="administration-code-row">
            <code>
              {freshLink.url}
            </code>
            <button
              className="btn-secondary administration-copy"
              onClick={copyLink}
              title={t('adminPanel.copyLink')}
              aria-label={t('adminPanel.copyLink')}
            >
              {copied ? <Check size={16} /> : <Copy size={16} />}
            </button>
          </div>
        </div>
      )}

      <div className="administration-section">
        <h3>{t('adminPanel.allLinks')}</h3>
        {links.map(l => {
          const expired = new Date(l.expiresAt).getTime() < Date.now();
          const exhausted = l.usesCount >= l.maxUses;
          const dead = !!l.revokedAt || expired || exhausted;
          return (
            <div
              key={l.id}
              className="administration-row"
              style={{ opacity: dead ? 0.55 : 1 }}
            >
              <div className="administration-row__identity">
                <div className="administration-row__name">{l.label || <span className="administration-row__meta">{t('adminPanel.noLabel')}</span>}</div>
                <div className="administration-row__meta">
                  {l.usesCount}/{l.maxUses} {t('adminPanel.usedIssuedBy')} {l.createdByEmail} ·{' '}
                  {l.revokedAt ?t('adminPanel.revoked') : expired ?t('adminPanel.expired') : exhausted ?t('adminPanel.exhausted') : t('adminPanel.expiresOn', { date: formatDate(l.expiresAt) })}
                </div>
              </div>
              {!l.revokedAt && !expired && !exhausted && (
                <button
                  className="btn-secondary administration-action administration-action--danger"
                  onClick={() => onRun(() => api.revokeCreationLink(l.id),t('adminPanel.linkRevoked'))}
                >
                  <Trash2 size={13} /> {t('adminPanel.revoke')}
                </button>
              )}
            </div>
          );
        })}
        {links.length === 0 && <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>{t('adminPanel.noLinksIssuedYet')}</p>}
      </div>
    </div>
  );
};
