import React from 'react';
import type { Client } from '../types';
import { Phone, Calendar, Tag, ChevronRight, AlertCircle } from 'lucide-react';

interface ClientCardProps {
  client: Client;
  onSelect: (client: Client) => void;
  onLogVisit: (client: Client) => void;
}

export const ClientCard: React.FC<ClientCardProps> = ({ client, onSelect, onLogVisit }) => {
  const customFieldsKeys = Object.keys(client.customFields);

  return (
    <div 
      className="glass-panel"
      style={{
        padding: '24px',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
        transition: 'transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease',
        cursor: 'pointer',
        position: 'relative',
        overflow: 'hidden'
      }}
      onClick={() => onSelect(client)}
      onMouseEnter={(e) => {
        e.currentTarget.style.transform = 'translateY(-3px)';
        e.currentTarget.style.borderColor = 'var(--rose-gold-primary)';
        e.currentTarget.style.boxShadow = '0 12px 30px rgba(0,0,0,0.5), var(--shadow-glow)';
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.transform = 'translateY(0)';
        e.currentTarget.style.borderColor = 'var(--border-color)';
        e.currentTarget.style.boxShadow = 'var(--shadow-card)';
      }}
    >
      {/* Top Section */}
      <div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '14px' }}>
          <div>
            <h3 style={{ fontSize: '18px', color: '#fff', marginBottom: '4px' }}>{client.name}</h3>
            <p style={{ fontSize: '13px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Phone size={13} color="var(--rose-gold-primary)" /> {client.phone}
            </p>
          </div>
          <span style={{
            fontSize: '11px',
            fontWeight: 700,
            background: 'rgba(229, 184, 153, 0.1)',
            color: 'var(--rose-gold-primary)',
            padding: '4px 10px',
            borderRadius: '12px',
            border: '1px solid rgba(229, 184, 153, 0.2)'
          }}>
            {client.totalVisits} {client.totalVisits === 1 ? 'Visit' : 'Visits'}
          </span>
        </div>

        {/* Tags */}
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', marginBottom: '16px' }}>
          {client.tags.map(tag => {
            const isVip = tag.toLowerCase() === 'vip';
            const isSens = tag.toLowerCase().includes('sensitive');
            return (
              <span 
                key={tag} 
                className={`tag-badge ${isVip ? 'tag-vip' : isSens ? 'tag-sensitive' : ''}`}
              >
                <Tag size={10} /> {tag}
              </span>
            );
          })}
        </div>

        {/* Custom Fields Summary */}
        {customFieldsKeys.length > 0 && (
          <div style={{
            background: 'rgba(15, 14, 19, 0.5)',
            borderRadius: '10px',
            padding: '12px 14px',
            marginBottom: '16px',
            border: '1px dashed var(--border-color)'
          }}>
            <p style={{ fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--rose-gold-primary)', fontWeight: 600, marginBottom: '6px', display: 'flex', alignItems: 'center', gap: '4px' }}>
              <AlertCircle size={12} /> Custom Specs & Attributes
            </p>
            {customFieldsKeys.slice(0, 3).map(key => (
              <div key={key} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', marginBottom: '4px' }}>
                <span style={{ color: 'var(--text-muted)' }}>{key}:</span>
                <span style={{ color: 'var(--text-main)', fontWeight: 500, maxWidth: '160px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {String(client.customFields[key])}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Footer */}
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        paddingTop: '12px',
        borderTop: '1px solid rgba(255,255,255,0.05)'
      }}>
        <span style={{ fontSize: '12px', color: 'var(--text-dim)', display: 'flex', alignItems: 'center', gap: '4px' }}>
          <Calendar size={12} /> Updated {new Date(client.updatedAt).toLocaleDateString()}
        </span>
        <button 
          onClick={(e) => {
            e.stopPropagation();
            onLogVisit(client);
          }}
          className="btn-secondary"
          style={{ padding: '6px 12px', fontSize: '12px' }}
        >
          Log Visit <ChevronRight size={14} />
        </button>
      </div>
    </div>
  );
};
