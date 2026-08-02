import React, { useCallback, useEffect, useState } from 'react';
import { X, Users, UserPlus, Check, Trash2, Shield } from 'lucide-react';
import type { OrgMember, OrgRole } from '../types';
import { api, ApiError } from '../services/api';
import { useAuth } from '../auth/AuthContext';
import { useOrg } from '../auth/OrgContext';

interface MembersModalProps {
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
 * Rendered only for `ORG_ADMIN` — see `Header.tsx`. That is a convenience, not
 * the control: the backend rejects every one of these calls from a plain
 * member with `ADMIN_REQUIRED`, which is what actually enforces the rule. If
 * the two ever disagree, the server is right.
 */
export const MembersModal: React.FC<MembersModalProps> = ({ onClose }) => {
  const { user } = useAuth();
  const { current, refresh: refreshOrgs } = useOrg();

  const [members, setMembers] = useState<OrgMember[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState<OrgRole>('ORG_USER');
  const [inviting, setInviting] = useState(false);

  const orgId = current?.id ?? null;

  const load = useCallback(async () => {
    if (!orgId) return;
    setLoading(true);
    setError('');
    try {
      setMembers(await api.getOrganizationMembers(orgId));
    } catch (err) {
      setError(err instanceof ApiError && err.body.error ? err.body.error : 'Could not load members.');
    } finally {
      setLoading(false);
    }
  }, [orgId]);

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
      // as well rather than left stale.
      await refreshOrgs();
    } catch (err) {
      setError(err instanceof ApiError && err.body.error ? err.body.error : 'That action failed.');
    }
  }

  async function handleInvite(e: React.FormEvent) {
    e.preventDefault();
    if (!orgId) return;
    setInviting(true);
    await run(
      () => api.inviteMember(orgId, inviteEmail.trim().toLowerCase(), inviteRole),
      'Invitation sent.'
    );
    setInviteEmail('');
    setInviting(false);
  }

  if (!orgId) return null;

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
            <Users size={20} /> {current?.name} — Members
          </h2>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
            <X size={22} />
          </button>
        </div>

        <div style={{ padding: '24px', flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '24px' }}>
          {error && <div style={bannerStyle('error')}>{error}</div>}
          {notice && <div style={bannerStyle('success')}>{notice}</div>}

          {/* Pending approvals first — this is the queue that needs action. */}
          {pending.length > 0 && (
            <section style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              <h3 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)' }}>
                Waiting for approval ({pending.length})
              </h3>
              {pending.map(m => (
                <MemberRow key={m.userId} member={m}>
                  <button
                    className="btn-rose"
                    style={{ padding: '6px 12px', fontSize: '12px' }}
                    onClick={() => run(() => api.approveMember(orgId, m.userId), `${m.fullName} approved.`)}
                  >
                    <Check size={14} /> Approve
                  </button>
                  <IconButton
                    title="Decline"
                    onClick={() => run(() => api.removeMember(orgId, m.userId), 'Request declined.')}
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
                Invited ({invited.length})
              </h3>
              {invited.map(m => (
                <MemberRow key={m.userId} member={m}>
                  <IconButton
                    title="Withdraw invitation"
                    onClick={() => run(() => api.removeMember(orgId, m.userId), 'Invitation withdrawn.')}
                  >
                    <Trash2 size={15} />
                  </IconButton>
                </MemberRow>
              ))}
            </section>
          )}

          <section style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
            <h3 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)' }}>
              Members ({active.length})
            </h3>
            {loading ? (
              <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>Loading…</p>
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
                        `${m.fullName}'s role updated.`
                      )
                    }
                  >
                    <option value="ORG_USER">Member</option>
                    <option value="ORG_ADMIN">Administrator</option>
                  </select>
                  <IconButton
                    title={m.userId === user?.id ? 'Leave organization' : 'Remove from organization'}
                    onClick={() =>
                      run(
                        () => api.removeMember(orgId, m.userId),
                        m.userId === user?.id ? 'You left the organization.' : `${m.fullName} removed.`
                      )
                    }
                  >
                    <Trash2 size={15} />
                  </IconButton>
                </MemberRow>
              ))
            )}
            <p style={{ color: 'var(--text-muted)', fontSize: '12px' }}>
              Removing someone revokes their access immediately — their current session stops
              working on its next request. Clients and visits they entered stay with the
              organization.
            </p>
          </section>

          {/* Invite */}
          <form onSubmit={handleInvite} style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
            <h3 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <UserPlus size={16} /> Invite someone
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
                <option value="ORG_USER">Member</option>
                <option value="ORG_ADMIN">Administrator</option>
              </select>
              <button type="submit" className="btn-rose" disabled={inviting || !inviteEmail.trim()}>
                {inviting ? 'Sending…' : 'Invite'}
              </button>
            </div>
            <p style={{ color: 'var(--text-muted)', fontSize: '12px' }}>
              They need an account already — invitations match an existing email address.
            </p>
          </form>
        </div>
      </div>
    </div>
  );
};

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
