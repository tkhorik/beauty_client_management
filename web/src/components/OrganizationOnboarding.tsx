import React, { useEffect, useState } from 'react';
import { Building2, LogOut, Plus, UserPlus, Clock, Link2Off, ShieldCheck } from 'lucide-react';
import { api, ApiError } from '../services/api';
import { useAuth } from '../auth/AuthContext';
import { useOrg } from '../auth/OrgContext';
import { stripQueryString } from '../auth/route';

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

const fieldErrorStyle = {
  color: '#e87c8a',
  fontSize: '12px',
  marginTop: '5px',
} as const;

const bannerStyle = (kind: 'error' | 'success' | 'info') =>
  ({
    padding: '10px 14px',
    borderRadius: '8px',
    background:
      kind === 'error' ? 'rgba(220, 50, 80, 0.12)'
        : kind === 'success' ? 'rgba(45, 212, 191, 0.12)'
          : 'rgba(148, 163, 184, 0.12)',
    border: `1px solid ${
      kind === 'error' ? 'rgba(220, 50, 80, 0.3)'
        : kind === 'success' ? 'rgba(45, 212, 191, 0.35)'
          : 'rgba(148, 163, 184, 0.3)'
    }`,
    color: kind === 'error' ? '#e87c8a' : kind === 'success' ? '#2dd4bf' : 'var(--text-muted)',
    fontSize: '13px',
  }) as const;

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
}

export const OrganizationOnboarding: React.FC<OrganizationOnboardingProps> = ({ onOpenAdmin }) => {
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

  const [joinSlug, setJoinSlug] = useState('');
  const [joinError, setJoinError] = useState('');
  const [joinNotice, setJoinNotice] = useState('');
  const [joining, setJoining] = useState(false);

  const pending = organizations.filter(o => o.status !== 'ACTIVE');

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
    } catch (err) {
      if (err instanceof ApiError && err.body.errors) setCreateErrors(err.body.errors);
      else if (err instanceof ApiError && err.body.error) setCreateErrors({ name: err.body.error });
      else setCreateErrors({ name: 'Could not create the organization. Please try again.' });
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
          ? `You have joined ${result.name}.`
          : `Request sent to ${result.name}. An administrator has to approve it.`
      );
      setJoinSlug('');
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) setJoinError('No organization with that handle.');
      else if (err instanceof ApiError && err.body.error) setJoinError(err.body.error);
      else setJoinError('Could not send the request. Please try again.');
    } finally {
      setJoining(false);
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px' }}>
      <div className="glass-panel-glow" style={{ width: '560px', maxWidth: '95vw', borderRadius: '20px', padding: '32px' }}>

        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '8px' }}>
          <Building2 size={24} color="var(--rose-gold-primary)" />
          <h1 className="text-gradient" style={{ fontSize: '22px' }}>Choose an organization</h1>
        </div>
        <p style={{ color: 'var(--text-muted)', fontSize: '13px', marginBottom: '24px' }}>
          Clients and visits belong to an organization. Join one that already exists,
          or create one for your own salon if an administrator sent you an invitation link.
        </p>

        {pending.length > 0 && (
          <div style={{ ...bannerStyle('info'), marginBottom: '24px', display: 'flex', gap: '8px', alignItems: 'flex-start' }}>
            <Clock size={16} style={{ flexShrink: 0, marginTop: '1px' }} />
            <div>
              Waiting on approval:{' '}
              {pending.map(o => o.name).join(', ')}
            </div>
          </div>
        )}

        {/*
          Create — gated on a valid organization-creation link. Organization
          creation is no longer self-service (see AdminRoutes.kt /
          OrgCreationTokenService on the backend): without `?orgToken=…` in
          the URL this whole section is replaced by an explanatory message,
          and the backend would reject the request regardless even if this
          check were somehow bypassed client-side.
        */}
        {tokenStatus === 'valid' && (
          <form onSubmit={handleCreate} style={{ display: 'flex', flexDirection: 'column', gap: '14px', marginBottom: '32px' }}>
            <h2 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Plus size={16} /> Create a new organization
            </h2>
            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>
                Name
              </label>
              <input
                className="input-field"
                value={name}
                onChange={e => setName(e.target.value)}
                placeholder="Aura Downtown"
                aria-invalid={!!createErrors.name}
              />
              {createErrors.name && <div style={fieldErrorStyle}>{createErrors.name}</div>}
            </div>
            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>
                Handle (optional)
              </label>
              <input
                className="input-field"
                value={slug}
                onChange={e => setSlug(e.target.value)}
                placeholder="aura-downtown"
                aria-invalid={!!createErrors.slug}
              />
              <div style={{ color: 'var(--text-muted)', fontSize: '12px', marginTop: '5px' }}>
                This is what colleagues type to request access. Lowercase letters, numbers and
                hyphens. Derived from the name if you leave it blank.
              </div>
              {createErrors.slug && <div style={fieldErrorStyle}>{createErrors.slug}</div>}
            </div>
            <button type="submit" className="btn-rose" disabled={creating || !name.trim()}>
              {creating ? 'Creating…' : 'Create organization'}
            </button>
          </form>
        )}

        {tokenStatus === 'checking' && (
          <div style={{ ...bannerStyle('info'), marginBottom: '32px' }}>Checking your invitation link…</div>
        )}

        {tokenStatus === 'invalid' && (
          <div style={{ marginBottom: '32px' }}>
            <h2 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)', display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '10px' }}>
              <Link2Off size={16} /> Create a new organization
            </h2>
            <div style={bannerStyle(creationToken ? 'error' : 'info')}>
              {creationToken
                ? 'This invitation link is invalid, expired, or has already been used. Ask your administrator for a new one.'
                : 'Creating a new organization requires an invitation link from an administrator. If you were given one, open it directly.'}
            </div>
          </div>
        )}

        {/* Join */}
        <form onSubmit={handleJoin} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
          <h2 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
            <UserPlus size={16} /> Join an existing one
          </h2>
          <div>
            <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>
              Organization handle
            </label>
            <input
              className="input-field"
              value={joinSlug}
              onChange={e => setJoinSlug(e.target.value)}
              placeholder="aura-downtown"
              aria-invalid={!!joinError}
            />
            {joinError && <div style={fieldErrorStyle}>{joinError}</div>}
          </div>
          {joinNotice && <div style={bannerStyle('success')}>{joinNotice}</div>}
          <button type="submit" className="btn-rose" disabled={joining || !joinSlug.trim()}>
            {joining ? 'Sending…' : 'Request access'}
          </button>
        </form>

        <div style={{ marginTop: '28px', display: 'flex', alignItems: 'center', gap: '20px' }}>
          <button
            onClick={logout}
            style={{
              background: 'none',
              border: 'none',
              color: 'var(--text-muted)',
              cursor: 'pointer',
              fontSize: '13px',
              display: 'flex',
              alignItems: 'center',
              gap: '6px',
            }}
          >
            <LogOut size={14} /> Sign out
          </button>

          {/*
            The only way a SUPER_ADMIN with no organization yet — a freshly
            bootstrapped account, most likely — can reach the admin panel to
            issue their first creation link.
          */}
          {user?.globalRole === 'SUPER_ADMIN' && (
            <button
              onClick={onOpenAdmin}
              style={{
                background: 'none',
                border: 'none',
                color: 'var(--rose-gold-primary)',
                cursor: 'pointer',
                fontSize: '13px',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
              }}
            >
              <ShieldCheck size={14} /> Admin panel
            </button>
          )}
        </div>
      </div>
    </div>
  );
};
