import { useAppTranslation } from '../i18n/LocaleProvider';
import React, { useCallback, useEffect, useState } from 'react';
import { useDismiss } from './useDismiss';
import { X, Users, UserPlus, Check, Trash2, Shield, Link2, History, Ban, RotateCcw } from 'lucide-react';
import type { InviteLink, OrgMember, OrgRole } from '../types';
import { OrgActivity } from './OrgActivity';
import { api, writeErrorMessage } from '../services/api';
import { useAuth } from '../auth/AuthContext';
import { useOrg } from '../auth/OrgContext';
import { useLocale } from '../i18n/LocaleProvider';
import './Administration.css';

interface MembersModalProps {
  /**
   * The organization to manage — passed in rather than read from
   * `useOrg().current`, because this modal is opened from two places that mean
   * two different things by "the organization".
   *
   * From the header it is the active one. From the admin panel it is whichever
   * row a super admin clicked, which may be a salon they do not belong to and
   * is then *not* the active organization. Deriving the target from context
   * would have that second case silently edit the roster of whichever salon
   * happened to be selected — the wrong organization, with no error to show
   * for it, since the server would accept every call.
   */
  orgId: string;
  orgName: string;
  onClose: () => void;
}

/**
 * Membership management for an organization administrator.
 *
 * Rendered for `ORG_ADMIN` and for any `SUPER_ADMIN` — see `Header.tsx` and
 * `AdminPanel.tsx`. That is a convenience, not the control: the backend
 * rejects every one of these calls from a plain member with `ADMIN_REQUIRED`,
 * which is what actually enforces the rule. If the two ever disagree, the
 * server is right.
 *
 * The roster it renders includes `PENDING` and `INVITED` rows, so a super
 * admin reaching a foreign organization gets its approval queue too, not just
 * the members already inside.
 */
export const MembersModal: React.FC<MembersModalProps> = ({ orgId, orgName, onClose }) => {
  const { t } = useAppTranslation();
  const dismiss = useDismiss(onClose);
  const { user } = useAuth();
  const { organizations, refresh: refreshOrgs } = useOrg();

  const [members, setMembers] = useState<OrgMember[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState<OrgRole>('ORG_USER');
  const [inviting, setInviting] = useState(false);
  const [tab, setTab] = useState<'members' | 'activity'>('members');
  const { formatDate } = useLocale();
  const [inviteLinks, setInviteLinks] = useState<InviteLink[]>([]);
  /** The URL of the link issued in this session — the only time its token is visible. */
  const [issuedLink, setIssuedLink] = useState<string | null>(null);
  const [issuing, setIssuing] = useState(false);

  /**
   * Whether the target is one of the caller's own organizations, which decides
   * whether their own membership list is worth re-reading after an action —
   * see [run]. A super admin acting on a foreign organization changes nothing
   * about their own standing anywhere.
   */
  const isOwnOrganization = organizations.some(o => o.id === orgId);

  /**
   * The handle new staff would type, as a link that pre-fills it. A super
   * admin's organization list covers every organization, so this resolves for
   * them too; if it somehow does not, the control is simply not offered.
   */
  const slug = organizations.find(o => o.id === orgId)?.slug;
  const joinLink = slug ? `${window.location.origin}/?join=${encodeURIComponent(slug)}` : null;

  async function copyJoinLink() {
    if (!joinLink) return;
    setError('');
    setNotice('');
    try {
      await navigator.clipboard.writeText(joinLink);
      setNotice(t('membersModal.joinLinkCopied'));
    } catch {
      // Clipboard access can be refused (insecure origin, permissions). The
      // link is shown in full next to the button, so it can still be copied
      // by hand.
      setError(t('membersModal.joinLinkCopyFailed'));
    }
  }

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [roster, links] = await Promise.all([api.getOrganizationMembers(orgId), api.getInviteLinks(orgId)]);
      setMembers(roster);
      setInviteLinks(links);
    } catch (err) {
      setError(writeErrorMessage(err, t('membersModal.couldNotLoadMembers')));
    } finally {
      setLoading(false);
    }
  }, [orgId, t]);

  useEffect(() => {
    void load();
  }, [load]);

  /** Runs an admin action, then reloads so the list reflects what the server did. */
  async function run(action: () => Promise<void>, success: string) {
    setError('');
    setNotice('');
    try {
      await action();
      setNotice(success);
      await load();
      // Role and membership changes can affect the caller's own standing —
      // demoting yourself, for instance — so the organization list is re-read
      // as well rather than left stale. Only when the caller is actually in
      // this organization, though: for a super admin managing someone else's
      // salon there is no own standing to have changed, and refreshing would
      // spend a request to learn nothing.
      if (isOwnOrganization) await refreshOrgs();
    } catch (err) {
      setError(writeErrorMessage(err, t('membersModal.thatActionFailed')));
    }
  }

  async function issueInviteLink() {
    setError('');
    setNotice('');
    setIssuing(true);
    try {
      const { url } = await api.createInviteLink(orgId);
      setIssuedLink(url);
      setInviteLinks(await api.getInviteLinks(orgId));
      try {
        await navigator.clipboard.writeText(url);
        setNotice(t('membersModal.inviteLinkCreated'));
      } catch {
        setNotice(t('membersModal.inviteLinkCreatedNotCopied'));
      }
    } catch (err) {
      setError(writeErrorMessage(err, t('membersModal.thatActionFailed')));
    } finally {
      setIssuing(false);
    }
  }

  async function handleInvite(e: React.FormEvent) {
    e.preventDefault();
    setInviting(true);
    await run(
      () => api.inviteMember(orgId, inviteEmail.trim().toLowerCase(), inviteRole),t('membersModal.invitationSent')
    );
    setInviteEmail('');
    setInviting(false);
  }

  function confirmRemoval(member: OrgMember) {
    const identity = `${member.fullName} (${member.email})`;
    const isSelf = member.userId === user?.id;
    let message: string;
    let success: string;

    if (member.status === 'PENDING') {
      // Declining keeps a record, so the requester is told and cannot re-ask
      // straight away — unlike deleting the row, which this used to do.
      message = t('membersModal.confirmDecline', { identity, organization: orgName });
      if (window.confirm(message)) {
        void run(() => api.declineMember(orgId, member.userId), t('membersModal.requestDeclined'));
      }
      return;
    } else if (member.status === 'INVITED') {
      message = t('membersModal.confirmWithdraw', { identity, organization: orgName });
      success =t('membersModal.invitationWithdrawn');
    } else {
      message = isSelf
        ? t('membersModal.confirmLeave', { organization: orgName })
        : t('membersModal.confirmRemove', { identity, organization: orgName });
      success = isSelf ?t('membersModal.youLeftTheOrganization') : t('membersModal.memberRemoved', { name: member.fullName });
    }

    if (window.confirm(message)) {
      void run(() => api.removeMember(orgId, member.userId), success);
    }
  }

  function confirmRevoke(member: OrgMember) {
    const identity = `${member.fullName} (${member.email})`;
    if (window.confirm(t('membersModal.confirmRevoke', { identity, organization: orgName }))) {
      void run(() => api.revokeMember(orgId, member.userId), t('membersModal.accessRevoked', { name: member.fullName }));
    }
  }

  const pending = members.filter(m => m.status === 'PENDING');
  const suspended = members.filter(m => m.status === 'SUSPENDED');
  const invited = members.filter(m => m.status === 'INVITED');
  const active = members.filter(m => m.status === 'ACTIVE');

  return (
    <div
      className="dialog-overlay administration-overlay"
      {...dismiss}
    >
      <div
        className="dialog-panel administration-panel administration-panel--members"
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="members-modal-title"
        tabIndex={-1}
      >
        <div className="dialog-header administration-header">
          <h2 id="members-modal-title" className="administration-title">
            <Users size={20} /> {orgName}
          </h2>
          <button type="button" className="icon-button" aria-label={t('common.close')} onClick={onClose}>
            <X size={22} />
          </button>
        </div>

        <nav className="administration-nav" aria-label={orgName}>
          <TabButton active={tab === 'members'} onClick={() => setTab('members')}>
            <Users size={14} /> {t('membersModal.membersTab')}
          </TabButton>
          <TabButton active={tab === 'activity'} onClick={() => setTab('activity')}>
            <History size={14} /> {t('membersModal.activityTab')}
          </TabButton>
        </nav>

        {tab === 'activity' ? (
          <div className="dialog-body administration-body">
            <OrgActivity orgId={orgId} />
          </div>
        ) : (
        <div className="dialog-body administration-body administration-stack">
          {error && <div className="notice notice--error" role="alert">{error}</div>}
          {notice && <div className="notice" role="status">{notice}</div>}

          {/* Pending approvals first — this is the queue that needs action. */}
          {pending.length > 0 && (
            <section className="administration-section">
              <h3>
                {t('membersModal.waitingForApproval')}{pending.length})
              </h3>
              {pending.map(m => (
                <MemberRow key={m.userId} member={m}>
                  <button
                    className="btn-rose"
                    style={{ padding: '6px 12px', fontSize: '12px' }}
                    onClick={() => run(() => api.approveMember(orgId, m.userId), t('membersModal.memberApproved', { name: m.fullName }))}
                  >
                    <Check size={14} /> {t('membersModal.approve')}
                  </button>
                  <IconButton
                    title={t('membersModal.decline')}
                    onClick={() => confirmRemoval(m)}
                  >
                    <Trash2 size={15} />
                  </IconButton>
                </MemberRow>
              ))}
            </section>
          )}

          {invited.length > 0 && (
            <section className="administration-section">
              <h3>
                {t('membersModal.invited')}{invited.length})
              </h3>
              {invited.map(m => (
                <MemberRow key={m.userId} member={m}>
                  <IconButton
                    title={t('membersModal.withdrawInvitation')}
                    onClick={() => confirmRemoval(m)}
                  >
                    <Trash2 size={15} />
                  </IconButton>
                </MemberRow>
              ))}
            </section>
          )}

          <section className="administration-section">
            <h3>
              {t('membersModal.members')}{active.length})
            </h3>
            {loading ? (
              <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>{t('membersModal.loading')}</p>
            ) : (
              active.map(m => (
                <MemberRow key={m.userId} member={m}>
                  <select
                    aria-label={t('membersModal.roleFor', { name: m.fullName })}
                    className="input-field"
                    style={{ padding: '6px 10px', fontSize: '12px', width: 'auto' }}
                    value={m.role}
                    onChange={e =>
                      run(
                        () => api.changeMemberRole(orgId, m.userId, e.target.value as OrgRole),
                        t('membersModal.roleUpdated', { name: m.fullName })
                      )
                    }
                  >
                    <option value="ORG_USER">{t('membersModal.member')}</option>
                    <option value="ORG_ADMIN">{t('membersModal.administrator')}</option>
                  </select>
                  {m.userId !== user?.id && (
                    <IconButton title={t('membersModal.revokeAccess')} onClick={() => confirmRevoke(m)}>
                      <Ban size={15} />
                    </IconButton>
                  )}
                  <IconButton
                    title={m.userId === user?.id ?t('membersModal.leaveOrganization') :t('membersModal.removeFromOrganization')}
                    onClick={() => confirmRemoval(m)}
                  >
                    <Trash2 size={15} />
                  </IconButton>
                </MemberRow>
              ))
            )}
            <p style={{ color: 'var(--text-muted)', fontSize: '12px' }}>
              {t('membersModal.removingSomeoneRevokesTheirAccessImmediatelyTheirCurrentSessionS')}
            </p>
          </section>

          {suspended.length > 0 && (
            <section className="administration-section">
              <h3>
                {t('membersModal.revokedSection', { count: suspended.length })}
              </h3>
              {suspended.map(m => (
                <MemberRow key={m.userId} member={m}>
                  <button
                    className="btn-secondary"
                    style={{ padding: '6px 12px', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '4px' }}
                    onClick={() => run(() => api.restoreMember(orgId, m.userId), t('membersModal.accessRestored', { name: m.fullName }))}
                  >
                    <RotateCcw size={14} /> {t('membersModal.restoreAccess')}
                  </button>
                  <IconButton title={t('membersModal.removeFromOrganization')} onClick={() => confirmRemoval(m)}>
                    <Trash2 size={15} />
                  </IconButton>
                </MemberRow>
              ))}
            </section>
          )}

          {/* Invite */}
          <form onSubmit={handleInvite} className="administration-section">
            <h3 className="administration-section__title">
              <UserPlus size={16} /> {t('membersModal.inviteSomeone')}
            </h3>
            <div className="administration-form-row">
              <label className="administration-field"><span className="sr-only">{t('membersModal.email')}</span>
              <input
                className="input-field"
                type="email"
                value={inviteEmail}
                onChange={e => setInviteEmail(e.target.value)}
                placeholder="colleague@example.com"
              />
              </label><label className="administration-field"><span>{t('membersModal.member')}</span><select
                className="input-field"
                style={{ width: 'auto' }}
                value={inviteRole}
                onChange={e => setInviteRole(e.target.value as OrgRole)}
              >
                <option value="ORG_USER">{t('membersModal.member')}</option>
                <option value="ORG_ADMIN">{t('membersModal.administrator')}</option>
              </select>
              </label>
              <button type="submit" className="btn-rose" disabled={inviting || !inviteEmail.trim()}>
                {inviting ?t('membersModal.sending') :t('membersModal.invite')}
              </button>
            </div>
            <p style={{ color: 'var(--text-muted)', fontSize: '12px' }}>
              {t('membersModal.theyNeedAnAccountAlreadyInvitationsMatchAnExistingEmailAddress')}
            </p>
          </form>

          <section className="administration-section">
            <h3 className="administration-section__title">
              <Link2 size={16} /> {t('membersModal.inviteLinksTitle')}
            </h3>
            <div className="administration-code-row">
              {issuedLink && (
                <code className="administration-code">{issuedLink}</code>
              )}
              <button type="button" className="btn-rose" disabled={issuing} onClick={() => void issueInviteLink()}>
                {t('membersModal.generateInviteLink')}
              </button>
            </div>
            <p style={{ color: 'var(--text-muted)', fontSize: '12px' }}>{t('membersModal.inviteLinksHint')}</p>
            {inviteLinks.length > 0 && (
              <>
                <h4 style={{ fontSize: '13px', color: 'var(--text-muted)' }}>
                  {t('membersModal.activeInviteLinks', { count: inviteLinks.length })}
                </h4>
                {inviteLinks.map(link => (
                  <div key={link.id} className="administration-row administration-row--compact">
                    <span className="administration-row__meta">
                      {t('membersModal.inviteLinkExpires', { name: link.createdByName, date: formatDate(link.expiresAt) })}
                    </span>
                    <button
                      type="button"
                      className="btn-secondary administration-action"
                      onClick={() => run(() => api.revokeInviteLink(orgId, link.id), t('membersModal.inviteLinkRevoked'))}
                    >
                      <Ban size={14} /> {t('adminPanel.revoke')}
                    </button>
                  </div>
                ))}
              </>
            )}
          </section>

          {joinLink && (
            <section className="administration-section">
              <h3 className="administration-section__title">
                <Link2 size={16} /> {t('membersModal.joinLinkTitle')}
              </h3>
              <div className="administration-code-row">
                <code className="administration-code">{joinLink}</code>
                <button type="button" className="btn-secondary" onClick={() => void copyJoinLink()}>
                  {t('membersModal.copyJoinLink')}
                </button>
              </div>
              <p style={{ color: 'var(--text-muted)', fontSize: '12px' }}>{t('membersModal.joinLinkHint')}</p>
            </section>
          )}
        </div>
        )}
      </div>
    </div>
  );
};

const TabButton: React.FC<{ active: boolean; onClick: () => void; children: React.ReactNode }> = ({ active, onClick, children }) => (
  <button
    type="button"
    aria-pressed={active}
    onClick={onClick}
    className="administration-nav__button"
  >
    {children}
  </button>
);

const MemberRow: React.FC<{ member: OrgMember; children: React.ReactNode }> = ({ member, children }) => {
  const { t } = useAppTranslation();
  return (
  <div
    className="administration-row"
  >
    <div className="administration-row__identity">
      <div className="administration-row__name">
        {member.role === 'ORG_ADMIN' && <Shield size={13} />}
        {member.fullName}
      </div>
      <div className="administration-row__meta">
        {member.email}
        {!member.emailVerified && (
          <span className="administration-badge">{t('membersModal.emailNotConfirmed')}</span>
        )}
      </div>
    </div>
    <div className="administration-actions">{children}</div>
  </div>
  );
};

const IconButton: React.FC<{ title: string; onClick: () => void; children: React.ReactNode }> = ({
  title,
  onClick,
  children,
}) => (
  <button
    title={title}
    aria-label={title}
    onClick={onClick}
    type="button"
    className="icon-button"
  >
    {children}
  </button>
);
