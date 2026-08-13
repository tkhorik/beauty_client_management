import React from 'react';
import { Search, Plus, Sparkles, Filter, Settings, Users, ShieldCheck } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { useOrg } from '../auth/OrgContext';

interface HeaderProps {
  searchQuery: string;
  onSearchChange: (q: string) => void;
  selectedTag: string;
  onTagSelect: (tag: string) => void;
  onOpenNewClient: () => void;
  onOpenNewVisit: () => void;
  onOpenSettings: () => void;
  onOpenMembers: () => void;
  onOpenAdmin: () => void;
  totalClients: number;
}

export const Header: React.FC<HeaderProps> = ({
  searchQuery,
  onSearchChange,
  selectedTag,
  onTagSelect,
  onOpenNewClient,
  onOpenNewVisit,
  onOpenSettings,
  onOpenMembers,
  onOpenAdmin,
  totalClients
}) => {
  const { user } = useAuth();
  const { current, activeOrganizations, offline, select } = useOrg();
  const tagsList = ['All', 'VIP', 'Sensitive Skin', 'Lash Extensions', 'Hair Coloring', 'Skin Treatment'];

  return (
    <header className="glass-panel-glow" style={{ padding: '20px 28px', marginBottom: '28px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '16px' }}>
        
        {/* Brand */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <div style={{
            width: '44px',
            height: '44px',
            borderRadius: '12px',
            background: 'linear-gradient(135deg, var(--rose-gold-primary) 0%, var(--rose-gold-dark) 100%)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: '0 4px 15px rgba(229, 184, 153, 0.3)'
          }}>
            <Sparkles size={24} color="#0f0e13" />
          </div>
          <div>
            <h1 className="text-gradient" style={{ fontSize: '24px', lineHeight: '1.2' }}>Aura Beauty Log</h1>
            <p style={{ color: 'var(--text-muted)', fontSize: '13px', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <span style={{ width: '8px', height: '8px', borderRadius: '50%', background: '#2dd4bf' }}></span>
              Client & Visit Procedure Studio ({totalClients} Active Profiles)
            </p>
          </div>
        </div>

        {/* Action Buttons */}
        <div style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
          {/*
            Organization switcher. Rendered as a plain <select> rather than a
            fancy menu because getting this wrong is expensive: the visible
            label here is the only thing telling a user which salon's records
            they are about to edit.
          */}
          {current && (
            <select
              className="input-field"
              value={current.id}
              onChange={(e) => select(e.target.value)}
              title={offline ? 'Offline demo data — the backend is unreachable' : 'Active organization'}
              aria-label="Active organization"
              style={{ width: 'auto', padding: '9px 12px', fontSize: '13px' }}
              disabled={activeOrganizations.length <= 1}
            >
              {activeOrganizations.map(org => (
                <option key={org.id} value={org.id}>{org.name}</option>
              ))}
            </select>
          )}

          {/*
            Shown to administrators only — a convenience, not the control. The
            backend refuses these calls from a plain member regardless of what
            the UI renders.
          */}
          {current?.role === 'ORG_ADMIN' && !offline && (
            <button
              className="btn-secondary"
              onClick={onOpenMembers}
              title="Manage members"
              aria-label="Manage members"
              style={{ padding: '10px', display: 'flex' }}
            >
              <Users size={18} />
            </button>
          )}

          {/*
            System-wide, not organization-scoped — visible regardless of which
            organization is currently active. The backend's requireSuperAdmin()
            guard is the real gate; this is just where the entry point lives.
          */}
          {user?.globalRole === 'SUPER_ADMIN' && !offline && (
            <button
              className="btn-secondary"
              onClick={onOpenAdmin}
              title="Admin panel"
              aria-label="Admin panel"
              style={{ padding: '10px', display: 'flex' }}
            >
              <ShieldCheck size={18} />
            </button>
          )}

          <button className="btn-secondary" onClick={onOpenNewVisit}>
            <Plus size={16} /> Log New Visit
          </button>
          <button className="btn-rose" onClick={onOpenNewClient}>
            <Plus size={18} /> New Client Profile
          </button>
          <button
            className="btn-secondary"
            onClick={onOpenSettings}
            title={user ? `Signed in as ${user.fullName}` : 'Account settings'}
            aria-label="Account settings"
            style={{ padding: '10px', display: 'flex' }}
          >
            <Settings size={18} />
          </button>
        </div>
      </div>

      {/* Search & Tag Filter Bar */}
      <div style={{ display: 'flex', gap: '16px', marginTop: '20px', alignItems: 'center', flexWrap: 'wrap' }}>
        <div style={{ flex: '1 1 300px', position: 'relative' }}>
          <Search size={18} style={{ position: 'absolute', left: '14px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
          <input
            type="text"
            className="input-field"
            placeholder="Search clients by name, phone, allergies, formulas, or procedure notes..."
            value={searchQuery}
            onChange={(e) => onSearchChange(e.target.value)}
            style={{ paddingLeft: '42px', height: '42px' }}
          />
        </div>

        {/* Tag Filters */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', overflowX: 'auto', paddingBottom: '4px' }}>
          <Filter size={16} style={{ color: 'var(--text-dim)', marginRight: '4px' }} />
          {tagsList.map(tag => {
            const isActive = (tag === 'All' && !selectedTag) || selectedTag === tag;
            return (
              <button
                key={tag}
                onClick={() => onTagSelect(tag === 'All' ? '' : tag)}
                style={{
                  background: isActive ? 'var(--rose-gold-primary)' : 'rgba(255,255,255,0.05)',
                  color: isActive ? '#0f0e13' : 'var(--text-muted)',
                  border: isActive ? 'none' : '1px solid var(--border-color)',
                  padding: '6px 14px',
                  borderRadius: '20px',
                  fontSize: '12px',
                  fontWeight: 600,
                  cursor: 'pointer',
                  whiteSpace: 'nowrap',
                  transition: 'all 0.2s ease'
                }}
              >
                {tag}
              </button>
            );
          })}
        </div>
      </div>
    </header>
  );
};
