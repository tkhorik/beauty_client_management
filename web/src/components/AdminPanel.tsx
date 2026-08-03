import React, { useCallback, useEffect, useState } from 'react';
import { X, ShieldCheck, Users, Building2, Link2, Copy, Check, Trash2, Ban, RotateCcw } from 'lucide-react';
import type { AdminUser, AdminOrganization, OrganizationCreationLink } from '../types';
import { api, ApiError } from '../services/api';
import { useAuth } from '../auth/AuthContext';

interface AdminPanelProps {
  onClose: () => void;
}

type Tab = 'users' | 'organizations' | 'links';

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
  const { user } = useAuth();
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
      setError(err instanceof ApiError && err.body.error ? err.body.error : 'Could not load admin data.');
    } finally {
      setInitialLoading(false);
    }
  }, []);

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
      setError(err instanceof ApiError && err.body.error ? err.body.error : 'That action failed.');
    }
  }

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
          width: '820px',
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
            <ShieldCheck size={20} /> Admin panel
          </h2>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
            <X size={22} />
          </button>
        </div>

        <div style={{ display: 'flex', gap: '4px', padding: '12px 24px 0', borderBottom: '1px solid var(--border-color)' }}>
          <TabButton active={tab === 'users'} onClick={() => setTab('users')} icon={<Users size={15} />} label={`Users (${users.length})`} />
          <TabButton active={tab === 'organizations'} onClick={() => setTab('organizations')} icon={<Building2 size={15} />} label={`Organizations (${orgs.length})`} />
          <TabButton active={tab === 'links'} onClick={() => setTab('links')} icon={<Link2 size={15} />} label={`Creation links (${links.length})`} />
        </div>

        <div style={{ padding: '24px', flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '16px' }}>
          {error && <div style={bannerStyle('error')}>{error}</div>}
          {notice && <div style={bannerStyle('success')}>{notice}</div>}

          {initialLoading ? (
            <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>Loading…</p>
          ) : tab === 'users' ? (
            <UsersTab users={users} selfId={user?.id} onRun={run} />
          ) : tab === 'organizations' ? (
            <OrganizationsTab orgs={orgs} />
          ) : (
            <LinksTab links={links} onRun={run} freshLink={freshLink} onFreshLink={setFreshLink} />
          )}
        </div>
      </div>
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
    style={{
      display: 'flex',
      alignItems: 'center',
      gap: '6px',
      padding: '10px 14px',
      background: 'none',
      border: 'none',
      borderBottom: active ? '2px solid var(--rose-gold-primary)' : '2px solid transparent',
      color: active ? 'var(--rose-gold-primary)' : 'var(--text-muted)',
      fontSize: '13px',
      fontWeight: 600,
      cursor: 'pointer',
    }}
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
}> = ({ users, selfId, onRun }) => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
    {users.map(u => (
      <div
        key={u.id}
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
            {u.fullName}
            {u.globalRole === 'SUPER_ADMIN' && (
              <span style={{ fontSize: '10px', color: 'var(--rose-gold-primary)', border: '1px solid var(--rose-gold-primary)', borderRadius: '999px', padding: '1px 7px' }}>
                SUPER ADMIN
              </span>
            )}
            {u.suspendedAt && (
              <span style={{ fontSize: '10px', color: '#e87c8a', border: '1px solid rgba(220,50,80,0.4)', borderRadius: '999px', padding: '1px 7px' }}>
                SUSPENDED
              </span>
            )}
          </div>
          <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            {u.email} · {u.organizationCount} organization{u.organizationCount === 1 ? '' : 's'}
            {!u.emailVerified && ' · unverified'}
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          {u.id === selfId ? (
            <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>You</span>
          ) : u.suspendedAt ? (
            <button
              className="btn-secondary"
              style={{ padding: '6px 12px', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '6px' }}
              onClick={() => onRun(() => api.setUserSuspended(u.id, false), `${u.fullName} unsuspended.`)}
            >
              <RotateCcw size={13} /> Unsuspend
            </button>
          ) : (
            <button
              className="btn-secondary"
              style={{ padding: '6px 12px', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '6px', color: '#e87c8a' }}
              onClick={() => onRun(() => api.setUserSuspended(u.id, true), `${u.fullName} suspended.`)}
            >
              <Ban size={13} /> Suspend
            </button>
          )}
        </div>
      </div>
    ))}
  </div>
);

// ---------------------------------------------------------------------------
// Organizations
// ---------------------------------------------------------------------------

const OrganizationsTab: React.FC<{ orgs: AdminOrganization[] }> = ({ orgs }) => (
  <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
    {orgs.map(o => (
      <div
        key={o.id}
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
          <div style={{ fontSize: '14px' }}>{o.name}</div>
          <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            {o.slug} · created by {o.createdByEmail ?? 'unknown'}
          </div>
        </div>
        <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
          {o.memberCount} active member{o.memberCount === 1 ? '' : 's'}
        </div>
      </div>
    ))}
    {orgs.length === 0 && <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>No organizations yet.</p>}
  </div>
);

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
      const url = `${window.location.origin}/?orgToken=${encodeURIComponent(result.token)}`;
      onFreshLink({ url, token: result.token });
      setLabel('');
      await onRun(async () => {}, 'Link created.');
    } catch (err) {
      setIssueError(err instanceof ApiError && err.body.error ? err.body.error : 'Could not create the link.');
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
    <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
      <form onSubmit={handleIssue} style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
        <h3 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)' }}>Issue a new link</h3>
        <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
          <input
            className="input-field"
            style={{ flex: '1 1 200px' }}
            value={label}
            onChange={e => setLabel(e.target.value)}
            placeholder="Label (optional) — e.g. Q3 salon batch"
          />
          <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', color: 'var(--text-muted)' }}>
            Uses
            <input
              className="input-field"
              style={{ width: '70px' }}
              type="number"
              min={1}
              value={maxUses}
              onChange={e => setMaxUses(Math.max(1, Number(e.target.value) || 1))}
            />
          </label>
          <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', color: 'var(--text-muted)' }}>
            Expires in
            <select
              className="input-field"
              style={{ width: 'auto' }}
              value={expiresInHours}
              onChange={e => setExpiresInHours(Number(e.target.value))}
            >
              <option value={24}>1 day</option>
              <option value={168}>7 days</option>
              <option value={720}>30 days</option>
              <option value={8760}>1 year</option>
            </select>
          </label>
          <button type="submit" className="btn-rose" disabled={issuing}>
            {issuing ? 'Creating…' : 'Create link'}
          </button>
        </div>
        {issueError && <div style={bannerStyle('error')}>{issueError}</div>}
      </form>

      {freshLink && (
        <div style={{ ...bannerStyle('success'), display: 'flex', flexDirection: 'column', gap: '8px' }}>
          <div>This link is shown once — copy it now and hand it to whoever should create the organization.</div>
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <code style={{ flex: 1, fontSize: '12px', wordBreak: 'break-all', background: 'rgba(0,0,0,0.3)', padding: '8px 10px', borderRadius: '6px' }}>
              {freshLink.url}
            </code>
            <button
              className="btn-secondary"
              style={{ padding: '8px', display: 'flex' }}
              onClick={copyLink}
              title="Copy link"
              aria-label="Copy link"
            >
              {copied ? <Check size={16} /> : <Copy size={16} />}
            </button>
          </div>
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
        <h3 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)' }}>All links</h3>
        {links.map(l => {
          const expired = new Date(l.expiresAt).getTime() < Date.now();
          const exhausted = l.usesCount >= l.maxUses;
          const dead = !!l.revokedAt || expired || exhausted;
          return (
            <div
              key={l.id}
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: '12px',
                padding: '10px 14px',
                borderRadius: '10px',
                border: '1px solid var(--border-color)',
                opacity: dead ? 0.55 : 1,
                flexWrap: 'wrap',
              }}
            >
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: '14px' }}>{l.label || <span style={{ color: 'var(--text-muted)' }}>(no label)</span>}</div>
                <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                  {l.usesCount}/{l.maxUses} used · issued by {l.createdByEmail} ·{' '}
                  {l.revokedAt ? 'revoked' : expired ? 'expired' : exhausted ? 'exhausted' : `expires ${new Date(l.expiresAt).toLocaleDateString()}`}
                </div>
              </div>
              {!l.revokedAt && !expired && !exhausted && (
                <button
                  className="btn-secondary"
                  style={{ padding: '6px 12px', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '6px', color: '#e87c8a' }}
                  onClick={() => onRun(() => api.revokeCreationLink(l.id), 'Link revoked.')}
                >
                  <Trash2 size={13} /> Revoke
                </button>
              )}
            </div>
          );
        })}
        {links.length === 0 && <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>No links issued yet.</p>}
      </div>
    </div>
  );
};
