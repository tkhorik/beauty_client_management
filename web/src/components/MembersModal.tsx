import { useAppTranslation } from '../i18n/LocaleProvider';
import React, { useCallback, useEffect, useState } from 'react';
import { X, Users, UserPlus, Check, Trash2, Shield, Link2, History } from 'lucide-react';
import type { OrgMember, OrgRole } from '../types';
import { OrgActivity } from './OrgActivity';
import { api, writeErrorMessage } from '../services/api';
import { useAuth } from '../auth/AuthContext';
import { useOrg } from '../auth/OrgContext';

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

const bannerStyle = (kind: 'error' | 'success') =>
  ({
    padding: '10px 14px',
    borderRadius: '8px',
    background: kind === 'error' ? 'rgba(220, 50, 80, 0.12)' : 'rgba(45, 212, 191, 0.12)',
    border: `1px solid ${kind === 'error' ? 'rgba(220, 50, 80, 0.3)' : 'rgba(45, 212, 191, 0.35)'}`,
    color: kind === 'error' ? '#e87c8a' : '#2dd4bf',
    fontSize: '13px',
  }) as const;

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
      setMembers(await api.getOrganizationMembers(orgId));
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

  const pending = members.filter(m => m.status === 'PENDING');
  const invited = members.filter(m => m.status === 'INVITED');
  const active = members.filter(m => m.status === 'ACTIVE');

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.85)',
        backdropFilter: 'blur(10px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
        padding: '20px',
      }}
      onClick={onClose}
    >
      <div
        className="glass-panel-glow"
        style={{
          width: '620px',
          maxWidth: '95vw',
          maxHeight: '90vh',
          borderRadius: '20px',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}
        onClick={e => e.stopPropagation()}
      >
        <div
          style={{
            padding: '20px 24px',
            borderBottom: '1px solid var(--border-color)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            background: 'rgba(15, 14, 19, 0.9)',
          }}
        >
          <h2 className="text-gradient" style={{ fontSize: '20px', display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Users size={20} /> {orgName}
          </h2>
          <button aria-label={t('common.close')} onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
            <X size={22} />
          </button>
        </div>

        <div role="tablist" style={{ display: 'flex', gap: '8px', padding: '12px 24px 0' }}>
          <TabButton active={tab === 'members'} onClick={() => setTab('members')}>
            <Users size={14} /> {t('membersModal.membersTab')}
          </TabButton>
          <TabButton active={tab === 'activity'} onClick={() => setTab('activity')}>
            <History size={14} /> {t('membersModal.activityTab')}
          </TabButton>
        </div>

        {tab === 'activity' ? (
          <div style={{ padding: '24px', flex: 1, overflowY: 'auto' }}>
            <OrgActivity orgId={orgId} />
          </div>
        ) : (
        <div style={{ padding: '24px', flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '24px' }}>
          {error && <div style={bannerStyle('error')}>{error}</div>}
          {notice && <div style={bannerStyle('success')}>{notice}</div>}

          {/* Pending approvals first — this is the queue that needs action. */}
          {pending.length > 0 && (
            <section style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              <h3 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)' }}>
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
            <section style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              <h3 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)' }}>
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

          <section style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
            <h3 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)' }}>
              {t('membersModal.members')}{active.length})
            </h3>
            {loading ? (
              <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>{t('membersModal.loading')}</p>
            ) : (
              active.map(m => (
                <MemberRow key={m.userId} member={m}>
                  <select
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

          {/* Invite */}
          <form onSubmit={handleInvite} style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
            <h3 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <UserPlus size={16} /> {t('membersModal.inviteSomeone')}
            </h3>
            <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
              <input
                className="input-field"
                style={{ flex: '1 1 220px' }}
                type="email"
                value={inviteEmail}
                onChange={e => setInviteEmail(e.target.value)}
                placeholder="colleague@example.com"
              />
              <select
                className="input-field"
                style={{ width: 'auto' }}
                value={inviteRole}
                onChange={e => setInviteRole(e.target.value as OrgRole)}
              >
                <option value="ORG_USER">{t('membersModal.member')}</option>
                <option value="ORG_ADMIN">{t('membersModal.administrator')}</option>
              </select>
              <button type="submit" className="btn-rose" disabled={inviting || !inviteEmail.trim()}>
                {inviting ?t('membersModal.sending') :t('membersModal.invite')}
              </button>
            </div>
            <p style={{ color: 'var(--text-muted)', fontSize: '12px' }}>
              {t('membersModal.theyNeedAnAccountAlreadyInvitationsMatchAnExistingEmailAddress')}
            </p>
          </form>

          {joinLink && (
            <section style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              <h3 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
                <Link2 size={16} /> {t('membersModal.joinLinkTitle')}
              </h3>
              <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'center' }}>
                <code style={{ flex: '1 1 260px', fontSize: '12px', wordBreak: 'break-all', color: 'var(--text-muted)' }}>{joinLink}</code>
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
    role="tab"
    aria-selected={active}
    onClick={onClick}
    style={{
      background: active ? 'rgba(183, 110, 121, 0.15)' : 'none',
      border: '1px solid var(--border-color)',
      borderRadius: '8px',
      padding: '6px 12px',
      color: active ? 'var(--rose-gold-primary)' : 'var(--text-muted)',
      cursor: 'pointer',
      display: 'flex',
      alignItems: 'center',
      gap: '6px',
      fontSize: '13px',
    }}
  >
    {children}
  </button>
);

const MemberRow: React.FC<{ member: OrgMember; children: React.ReactNode }> = ({ member, children }) => (
  <div
    style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: '12px',
      padding: '10px 14px',
      borderRadius: '10px',
      border: '1px solid var(--border-color)',
      flexWrap: 'wrap',
    }}
  >
    <div style={{ minWidth: 0 }}>
      <div style={{ fontSize: '14px', display: 'flex', alignItems: 'center', gap: '6px' }}>
        {member.role === 'ORG_ADMIN' && <Shield size={13} color="var(--rose-gold-primary)" />}
        {member.fullName}
      </div>
      <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>{member.email}</div>
    </div>
    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>{children}</div>
  </div>
);

const IconButton: React.FC<{ title: string; onClick: () => void; children: React.ReactNode }> = ({
  title,
  onClick,
  children,
}) => (
  <button
    title={title}
    aria-label={title}
    onClick={onClick}
    style={{
      background: 'none',
      border: '1px solid var(--border-color)',
      borderRadius: '8px',
      padding: '6px',
      color: 'var(--text-muted)',
      cursor: 'pointer',
      display: 'flex',
    }}
  >
    {children}
  </button>
);
