import React, { useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import { api, ApiError } from '../services/api';
import { PASSWORD_MIN_LENGTH, validatePasswordLocally } from '../utils/passwordRules';
import { X, User, Lock, Settings as SettingsIcon } from 'lucide-react';
import { LanguageSelector } from './LanguageSelector';
import { useAppTranslation } from '../i18n/LocaleProvider';
import { translatedFieldErrors, translatedApiError } from '../i18n/errors';

interface SettingsModalProps {
  onClose: () => void;
}

type FieldErrors = Record<string, string>;

const fieldErrorStyle = {
  color: '#e87c8a',
  fontSize: '12px',
  marginTop: '5px',
} as const;

const bannerStyle = (kind: 'error' | 'success') =>
  ({
    padding: '10px 14px',
    borderRadius: '8px',
    background: kind === 'error' ? 'rgba(220, 50, 80, 0.12)' : 'rgba(45, 212, 191, 0.12)',
    border: `1px solid ${kind === 'error' ? 'rgba(220, 50, 80, 0.3)' : 'rgba(45, 212, 191, 0.35)'}`,
    color: kind === 'error' ? '#e87c8a' : '#2dd4bf',
    fontSize: '13px',
  }) as const;

export const SettingsModal: React.FC<SettingsModalProps> = ({ onClose }) => {
  const { user, updateUser, login, logout } = useAuth();
  const { t } = useAppTranslation();

  // -- Profile (name) form --
  const [fullName, setFullName] = useState(user?.fullName ?? '');
  const [profileErrors, setProfileErrors] = useState<FieldErrors>({});
  const [profileSaving, setProfileSaving] = useState(false);
  const [profileSuccess, setProfileSuccess] = useState(false);

  // -- Password form --
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmNewPassword, setConfirmNewPassword] = useState('');
  const [passwordErrors, setPasswordErrors] = useState<FieldErrors>({});
  const [passwordSaving, setPasswordSaving] = useState(false);
  const [passwordSuccess, setPasswordSuccess] = useState(false);

  if (!user) return null;

  async function handleProfileSubmit(e: React.FormEvent) {
    e.preventDefault();
    setProfileSuccess(false);

    const trimmed = fullName.trim();
    if (!trimmed) {
      setProfileErrors({ fullName: t('auth.nameRequired') });
      return;
    }
    setProfileErrors({});
    setProfileSaving(true);
    try {
      const updated = await api.updateProfile(trimmed);
      updateUser(updated);
      setProfileSuccess(true);
    } catch (err) {
      if (err instanceof ApiError && err.body.fieldErrors) {
        setProfileErrors(translatedFieldErrors(err.body));
      } else if (err instanceof ApiError) {
        setProfileErrors({ fullName: translatedApiError(err, t('common.error')) });
      } else {
        setProfileErrors({ fullName: t('common.error') });
      }
    } finally {
      setProfileSaving(false);
    }
  }

  async function handlePasswordSubmit(e: React.FormEvent) {
    e.preventDefault();
    setPasswordSuccess(false);

    const errors: FieldErrors = {};
    if (!currentPassword) {
      errors.currentPassword = t('settings.currentPasswordRequired');
    }
    const newPasswordError = validatePasswordLocally(newPassword);
    if (newPasswordError) {
      errors.newPassword = newPasswordError;
    } else if (newPassword === currentPassword) {
      errors.newPassword = t('apiErrors.PASSWORD_UNCHANGED');
    }
    if (newPassword !== confirmNewPassword) {
      errors.confirmNewPassword = t('auth.passwordsMismatch');
    }
    if (Object.keys(errors).length > 0) {
      setPasswordErrors(errors);
      return;
    }

    setPasswordErrors({});
    setPasswordSaving(true);
    try {
      const result = await api.changePassword(currentPassword, newPassword);
      // The backend just revoked every session, including the refresh token
      // behind this tab, and minted a fresh one — adopt it so this tab keeps
      // working instead of getting logged out on its own next request.
      login(result.token, result.user);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmNewPassword('');
      setPasswordSuccess(true);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setPasswordErrors({ currentPassword: t('apiErrors.CURRENT_PASSWORD_INCORRECT') });
      } else if (err instanceof ApiError && err.body.fieldErrors) {
        setPasswordErrors(translatedFieldErrors(err.body));
      } else {
        setPasswordErrors({ newPassword: t('common.error') });
      }
    } finally {
      setPasswordSaving(false);
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
          width: '520px',
          maxWidth: '95vw',
          maxHeight: '90vh',
          borderRadius: '20px',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
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
            <SettingsIcon size={20} /> {t('settings.title')}
          </h2>
          <button aria-label={t('common.close')} onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
            <X size={22} />
          </button>
        </div>

        <div style={{ padding: '24px', flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '28px' }}>

          <div>
            <h3 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)', marginBottom: '8px' }}>{t('language.label')}</h3>
            <p style={{ color: 'var(--text-muted)', fontSize: '12px', marginBottom: '10px' }}>{t('settings.languageDescription')}</p>
            <LanguageSelector />
          </div>

          {/* Email (read-only) */}
          <div>
            <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>
              {t('settingsModal.email')}
            </label>
            <input className="input-field" value={user.email} disabled style={{ opacity: 0.6, cursor: 'not-allowed' }} />
            <div style={{ color: 'var(--text-muted)', fontSize: '12px', marginTop: '5px' }}>
              {t('settingsModal.emailIsYourSignInIdentifierAndCanTBeChangedHereYet')}
            </div>
          </div>

          {/* Profile form */}
          <form onSubmit={handleProfileSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
            <h3 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <User size={16} /> {t('settingsModal.profile')}
            </h3>
            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>
                {t('settingsModal.fullName')}
              </label>
              <input
                type="text"
                className="input-field"
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                aria-invalid={!!profileErrors.fullName}
              />
              {profileErrors.fullName && <div style={fieldErrorStyle}>{profileErrors.fullName}</div>}
            </div>

            {profileSuccess && <div style={bannerStyle('success')}>{t('settingsModal.profileUpdated')}</div>}

            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button type="submit" className="btn-rose" disabled={profileSaving}>
                {profileSaving ? t('common.saving') :t('settingsModal.saveName')}
              </button>
            </div>
          </form>

          <div style={{ borderTop: '1px solid var(--border-color)' }} />

          {/* Password form */}
          <form onSubmit={handlePasswordSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
            <h3 style={{ fontSize: '14px', color: 'var(--rose-gold-primary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Lock size={16} /> {t('settingsModal.changePassword')}
            </h3>

            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>
                {t('settingsModal.currentPassword')}
              </label>
              <input
                type="password"
                autoComplete="current-password"
                className="input-field"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
                aria-invalid={!!passwordErrors.currentPassword}
              />
              {passwordErrors.currentPassword && <div style={fieldErrorStyle}>{passwordErrors.currentPassword}</div>}
            </div>

            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>
                {t('settingsModal.newPassword')}
              </label>
              <input
                type="password"
                autoComplete="new-password"
                minLength={PASSWORD_MIN_LENGTH}
                className="input-field"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                aria-invalid={!!passwordErrors.newPassword}
              />
              {passwordErrors.newPassword ? (
                <div style={fieldErrorStyle}>{passwordErrors.newPassword}</div>
              ) : (
                <div style={{ color: 'var(--text-muted)', fontSize: '12px', marginTop: '5px' }}>
                  {t('password.guidance', { min: PASSWORD_MIN_LENGTH })}
                </div>
              )}
            </div>

            <div>
              <label style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '6px', display: 'block' }}>
                {t('settingsModal.confirmNewPassword')}
              </label>
              <input
                type="password"
                autoComplete="new-password"
                className="input-field"
                value={confirmNewPassword}
                onChange={(e) => setConfirmNewPassword(e.target.value)}
                aria-invalid={!!passwordErrors.confirmNewPassword}
              />
              {passwordErrors.confirmNewPassword && <div style={fieldErrorStyle}>{passwordErrors.confirmNewPassword}</div>}
            </div>

            {passwordSuccess && (
              <div style={bannerStyle('success')}>
                {t('settingsModal.passwordChangedYouVeBeenSignedOutOfEveryOtherDevice')}
              </div>
            )}

            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button type="submit" className="btn-rose" disabled={passwordSaving}>
                {passwordSaving ?t('settingsModal.changing') :t('settingsModal.changePassword')}
              </button>
            </div>
          </form>

          <div style={{ borderTop: '1px solid var(--border-color)' }} />

          <button
            type="button"
            className="btn-secondary"
            onClick={() => {
              onClose();
              logout();
            }}
          >
            {t('settingsModal.signOut')}
          </button>
        </div>
      </div>
    </div>
  );
};
