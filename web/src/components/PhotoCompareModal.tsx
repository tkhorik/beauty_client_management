import React, { useState } from 'react';
import type { Attachment } from '../types';
import { X, Sliders, Columns } from 'lucide-react';

interface PhotoCompareModalProps {
  attachments: Attachment[];
  onClose: () => void;
}

export const PhotoCompareModal: React.FC<PhotoCompareModalProps> = ({ attachments, onClose }) => {
  const beforePhoto = attachments.find(a => a.tag === 'BEFORE') || attachments[0];
  const afterPhoto = attachments.find(a => a.tag === 'AFTER') || attachments[1] || attachments[0];

  const [sliderPos, setSliderPos] = useState(50);
  const [viewMode, setViewMode] = useState<'slider' | 'sideBySide'>('slider');

  return (
    <div style={{
      position: 'fixed',
      inset: 0,
      background: 'rgba(0, 0, 0, 0.85)',
      backdropFilter: 'blur(10px)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      zIndex: 1000,
      padding: '20px'
    }}>
      <div className="glass-panel-glow" style={{
        width: '900px',
        maxWidth: '95vw',
        maxHeight: '90vh',
        borderRadius: '20px',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden'
      }}>
        {/* Header */}
        <div style={{
          padding: '18px 24px',
          borderBottom: '1px solid var(--border-color)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          background: 'rgba(15, 14, 19, 0.8)'
        }}>
          <div>
            <h2 className="text-gradient" style={{ fontSize: '20px' }}>Procedure Before & After Comparison</h2>
            <p style={{ fontSize: '12px', color: 'var(--text-muted)' }}>Interactive side-by-side & slider inspection</p>
          </div>

          {/* Controls */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <div style={{ display: 'flex', background: 'rgba(255,255,255,0.05)', borderRadius: '10px', padding: '4px' }}>
              <button
                onClick={() => setViewMode('slider')}
                style={{
                  background: viewMode === 'slider' ? 'var(--rose-gold-primary)' : 'transparent',
                  color: viewMode === 'slider' ? '#0f0e13' : 'var(--text-muted)',
                  border: 'none',
                  padding: '6px 12px',
                  borderRadius: '6px',
                  fontSize: '12px',
                  fontWeight: 600,
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px'
                }}
              >
                <Sliders size={14} /> Slider Split
              </button>
              <button
                onClick={() => setViewMode('sideBySide')}
                style={{
                  background: viewMode === 'sideBySide' ? 'var(--rose-gold-primary)' : 'transparent',
                  color: viewMode === 'sideBySide' ? '#0f0e13' : 'var(--text-muted)',
                  border: 'none',
                  padding: '6px 12px',
                  borderRadius: '6px',
                  fontSize: '12px',
                  fontWeight: 600,
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px'
                }}
              >
                <Columns size={14} /> Side by Side
              </button>
            </div>

            <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
              <X size={24} />
            </button>
          </div>
        </div>

        {/* Content Area */}
        <div style={{ flex: 1, padding: '24px', overflowY: 'auto', display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
          {viewMode === 'slider' ? (
            /* Interactive Slider Comparison */
            <div style={{ width: '100%', maxWidth: '750px' }}>
              <div 
                style={{
                  position: 'relative',
                  width: '100%',
                  height: '420px',
                  borderRadius: '16px',
                  overflow: 'hidden',
                  userSelect: 'none',
                  boxShadow: '0 10px 30px rgba(0,0,0,0.6)',
                  border: '1px solid var(--border-color)'
                }}
                onMouseMove={(e) => {
                  const rect = e.currentTarget.getBoundingClientRect();
                  const x = Math.max(0, Math.min(e.clientX - rect.left, rect.width));
                  setSliderPos((x / rect.width) * 100);
                }}
                onTouchMove={(e) => {
                  const rect = e.currentTarget.getBoundingClientRect();
                  const touch = e.touches[0];
                  const x = Math.max(0, Math.min(touch.clientX - rect.left, rect.width));
                  setSliderPos((x / rect.width) * 100);
                }}
              >
                {/* AFTER Photo (Base) */}
                <img
                  src={afterPhoto?.fileUrl}
                  alt="After Procedure"
                  style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }}
                />
                <div style={{
                  position: 'absolute',
                  top: '16px',
                  right: '16px',
                  background: 'rgba(0,0,0,0.7)',
                  color: '#2dd4bf',
                  padding: '4px 12px',
                  borderRadius: '12px',
                  fontSize: '12px',
                  fontWeight: 700,
                  backdropFilter: 'blur(8px)',
                  border: '1px solid rgba(45,212,191,0.3)'
                }}>
                  AFTER PROCEDURE
                </div>

                {/* BEFORE Photo (Clipped Overlay) */}
                <div style={{
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  bottom: 0,
                  width: `${sliderPos}%`,
                  overflow: 'hidden',
                  borderRight: '2px solid var(--rose-gold-primary)'
                }}>
                  <img
                    src={beforePhoto?.fileUrl}
                    alt="Before Procedure"
                    style={{ width: '750px', height: '420px', objectFit: 'cover', maxWidth: 'none' }}
                  />
                  <div style={{
                    position: 'absolute',
                    top: '16px',
                    left: '16px',
                    background: 'rgba(0,0,0,0.7)',
                    color: 'var(--rose-gold-primary)',
                    padding: '4px 12px',
                    borderRadius: '12px',
                    fontSize: '12px',
                    fontWeight: 700,
                    backdropFilter: 'blur(8px)',
                    border: '1px solid rgba(229,184,153,0.3)'
                  }}>
                    BEFORE PROCEDURE
                  </div>
                </div>

                {/* Slider Handle */}
                <div style={{
                  position: 'absolute',
                  top: 0,
                  bottom: 0,
                  left: `${sliderPos}%`,
                  transform: 'translateX(-50%)',
                  width: '4px',
                  background: 'var(--rose-gold-primary)',
                  boxShadow: '0 0 15px rgba(229,184,153,0.8)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  pointerEvents: 'none'
                }}>
                  <div style={{
                    width: '32px',
                    height: '32px',
                    borderRadius: '50%',
                    background: 'var(--rose-gold-primary)',
                    color: '#0f0e13',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontWeight: 'bold',
                    fontSize: '14px',
                    boxShadow: '0 0 20px rgba(0,0,0,0.5)'
                  }}>
                    ↔
                  </div>
                </div>
              </div>

              {/* Slider Helper Note */}
              <p style={{ textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px', marginTop: '12px' }}>
                Drag or hover across the photo to split Before & After results
              </p>
            </div>
          ) : (
            /* Side by Side Mode */
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', width: '100%' }}>
              <div style={{ borderRadius: '16px', overflow: 'hidden', border: '1px solid var(--border-color)', background: 'rgba(0,0,0,0.4)', padding: '12px' }}>
                <div style={{ position: 'relative', height: '320px', borderRadius: '12px', overflow: 'hidden', marginBottom: '10px' }}>
                  <img src={beforePhoto?.fileUrl} alt="Before" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                  <span style={{ position: 'absolute', top: '10px', left: '10px', background: 'rgba(0,0,0,0.7)', color: 'var(--rose-gold-primary)', padding: '4px 10px', borderRadius: '8px', fontSize: '11px', fontWeight: 700 }}>BEFORE</span>
                </div>
                <p style={{ fontSize: '13px', color: 'var(--text-main)', fontWeight: 500 }}>{beforePhoto?.caption || 'Before procedure baseline photo'}</p>
              </div>

              <div style={{ borderRadius: '16px', overflow: 'hidden', border: '1px solid var(--border-color)', background: 'rgba(0,0,0,0.4)', padding: '12px' }}>
                <div style={{ position: 'relative', height: '320px', borderRadius: '12px', overflow: 'hidden', marginBottom: '10px' }}>
                  <img src={afterPhoto?.fileUrl} alt="After" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                  <span style={{ position: 'absolute', top: '10px', left: '10px', background: 'rgba(0,0,0,0.7)', color: '#2dd4bf', padding: '4px 10px', borderRadius: '8px', fontSize: '11px', fontWeight: 700 }}>AFTER</span>
                </div>
                <p style={{ fontSize: '13px', color: 'var(--text-main)', fontWeight: 500 }}>{afterPhoto?.caption || 'Post-procedure finished result'}</p>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
