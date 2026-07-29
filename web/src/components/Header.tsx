import React from 'react';
import { Search, Plus, Sparkles, Filter, Settings } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';

interface HeaderProps {
  searchQuery: string;
  onSearchChange: (q: string) => void;
  selectedTag: string;
  onTagSelect: (tag: string) => void;
  onOpenNewClient: () => void;
  onOpenNewVisit: () => void;
  onOpenSettings: () => void;
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
  totalClients
}) => {
  const { user } = useAuth();
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
