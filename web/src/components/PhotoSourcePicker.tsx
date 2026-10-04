import React, { useRef } from 'react';
import { Camera, Images } from 'lucide-react';
import { useAppTranslation } from '../i18n/LocaleProvider';

/**
 * Phones and tablets open the camera for `capture`; desktop browsers ignore it
 * and would show a second, identical file dialog, so the camera button is only
 * offered where a coarse pointer suggests a touch device.
 */
const prefersCameraButton = (): boolean =>
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(pointer: coarse)').matches;

interface PhotoSourcePickerProps {
  onFile: (file: File) => void;
  disabled?: boolean;
  color?: string;
}

/** "Take photo" (rear camera) and "Choose photo" buttons for one image. */
export const PhotoSourcePicker: React.FC<PhotoSourcePickerProps> = ({ onFile, disabled, color = 'var(--rose-gold-primary)' }) => {
  const { t } = useAppTranslation();
  const cameraInput = useRef<HTMLInputElement>(null);
  const galleryInput = useRef<HTMLInputElement>(null);
  const showCamera = prefersCameraButton();

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // Cleared so picking the same file again, e.g. after removing it, still fires a change.
    e.target.value = '';
    if (file) onFile(file);
  };

  const buttonStyle: React.CSSProperties = { padding: '8px 12px', fontSize: '12px', display: 'inline-flex', alignItems: 'center', gap: '6px' };

  return (
    <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', justifyContent: 'center' }}>
      {showCamera && (
        <button type="button" className="btn-secondary" style={buttonStyle} disabled={disabled} onClick={() => cameraInput.current?.click()}>
          <Camera size={16} color={color} /> {t('photoPicker.takePhoto')}
        </button>
      )}
      <button type="button" className="btn-secondary" style={buttonStyle} disabled={disabled} onClick={() => galleryInput.current?.click()}>
        <Images size={16} color={color} /> {t('photoPicker.choosePhoto')}
      </button>
      <input ref={cameraInput} type="file" accept="image/*" capture="environment" hidden onChange={handleChange} />
      <input ref={galleryInput} type="file" accept="image/*" hidden onChange={handleChange} />
    </div>
  );
};
