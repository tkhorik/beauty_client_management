import { useAppTranslation } from '../i18n/LocaleProvider';
import React, { useId, useState } from 'react';
import { useDismiss } from './useDismiss';
import type { Attachment } from '../types';
import { X, Sliders, Columns } from 'lucide-react';
import './ClientForms.css';

interface PhotoCompareModalProps {
  attachments: Attachment[];
  onClose: () => void;
}

export const PhotoCompareModal: React.FC<PhotoCompareModalProps> = ({ attachments, onClose }) => {
  const { t } = useAppTranslation();
  const dismiss = useDismiss(onClose);
  const id = useId();
  const beforePhoto = attachments.find(a => a.tag === 'BEFORE') || attachments[0];
  const afterPhoto = attachments.find(a => a.tag === 'AFTER') || attachments[1] || attachments[0];

  const [sliderPos, setSliderPos] = useState(50);
  const [viewMode, setViewMode] = useState<'slider' | 'sideBySide'>('slider');

  return (
    <div className="dialog-overlay" {...dismiss}>
      <div role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} tabIndex={-1} className="dialog-panel client-dialog client-dialog--wide">
        {/* Header */}
        <div className="dialog-header dialog-header--responsive">
          <div className="dialog-header__copy">
            <h2 id={`${id}-title`} className="dialog-title">{t('photoCompareModal.procedureBeforeAndAfterComparison')}</h2>
            <p className="dialog-subtitle">{t('photoCompareModal.interactiveSideBySideAndSliderInspection')}</p>
          </div>

          {/* Controls */}
          <div className="photo-toolbar">
            <div className="segmented">
              <button
                onClick={() => setViewMode('slider')}
                aria-pressed={viewMode === 'slider'}
              >
                <Sliders size={14} /> {t('photoCompareModal.sliderSplit')}
              </button>
              <button
                onClick={() => setViewMode('sideBySide')}
                aria-pressed={viewMode === 'sideBySide'}
              >
                <Columns size={14} /> {t('photoCompareModal.sideBySide')}
              </button>
            </div>

          </div>
          <button type="button" aria-label={t('common.close')} onClick={onClose} className="close-button">
            <X size={24} />
          </button>
        </div>

        {/* Content Area */}
        <div className="dialog-body" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
          {viewMode === 'slider' ? (
            /* Interactive Slider Comparison */
            <div style={{ width: '100%', maxWidth: '750px' }}>
              <div className="compare-stage"
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
                  alt={t('photoCompareModal.afterProcedure')}
                  loading="lazy" decoding="async"
                />
                <div className="compare-chip" style={{ right: '14px' }}>
                  {t('photoCompareModal.aFTERPROCEDURE')}
                </div>

                {/* BEFORE Photo (Clipped Overlay) */}
                <div style={{
                  position: 'absolute',
                  inset: 0,
                  clipPath: `inset(0 ${100 - sliderPos}% 0 0)`,
                  pointerEvents: 'none'
                }}>
                  <img
                    src={beforePhoto?.fileUrl}
                    alt={t('photoCompareModal.beforeProcedure')}
                    loading="lazy" decoding="async"
                  />
                  <div className="compare-chip" style={{ left: '14px' }}>
                    {t('photoCompareModal.bEFOREPROCEDURE')}
                  </div>
                </div>

                {/* Slider Handle */}
                <div className="compare-handle" style={{ left: `${sliderPos}%`, transform: 'translateX(-50%)' }}>
                  <span>
                    ↔
                  </span>
                </div>
              </div>

              <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '12px', marginTop: '14px' }}>
                <label htmlFor="photo-comparison-position" style={{ color: 'var(--text-muted)', fontSize: '13px', whiteSpace: 'nowrap' }}>
                  {t('photoCompareModal.comparisonPosition')}
                </label>
                <input
                  id="photo-comparison-position"
                  type="range"
                  min="0"
                  max="100"
                  step="1"
                  value={sliderPos}
                  onChange={(event) => setSliderPos(Number(event.target.value))}
                  aria-valuetext={t('photoCompareModal.beforePercentage', { count: Math.round(sliderPos) })}
                  style={{ flex: '1 1 140px', minWidth: 0, maxWidth: '100%', accentColor: 'var(--rose-gold-primary)' }}
                />
              </div>

              {/* Slider Helper Note */}
              <p style={{ textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px', marginTop: '12px' }}>
                {t('photoCompareModal.dragOrHoverAcrossThePhotoToSplitBeforeAndAfterResults')}
              </p>
            </div>
          ) : (
            /* Side by Side Mode */
            <div className="compare-side">
              <figure><img src={beforePhoto?.fileUrl} alt={t('photoCompareModal.before')} loading="lazy" decoding="async" /><figcaption>{beforePhoto?.caption || t('photoCompareModal.beforeCaption')}</figcaption></figure>
              <figure><img src={afterPhoto?.fileUrl} alt={t('photoCompareModal.after')} loading="lazy" decoding="async" /><figcaption>{afterPhoto?.caption || t('photoCompareModal.afterCaption')}</figcaption></figure>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
