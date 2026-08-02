import React, { useState } from 'react';
import { Building2, LogOut, Plus, UserPlus, Clock } from 'lucide-react';
import { api, ApiError } from '../services/api';
import { useAuth } from '../auth/AuthContext';
import { useOrg } from '../auth/OrgContext';

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
export const OrganizationOnboarding: React.FC = () => {
  const { logout } = useAuth();
  const { organizations, refresh } = useOrg();

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
    setCreateErrors({});
    setCreating(true);
    try {
      await api.createOrganization(name.trim(), slug.trim() || undefined);
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
          Clients and visits belong to an organization. Create one for your own salon,
          or join one that already exists.
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

        {/* Create */}
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

        <button
          onClick={logout}
          style={{
            marginTop: '28px',
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
      </div>
    </div>
  );
};
