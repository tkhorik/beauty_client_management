import React, { useId, useState } from 'react';
import { useDismiss } from './useDismiss';
import { useAuth } from '../auth/AuthContext';
import { api, ApiError } from '../services/api';
import { PASSWORD_MIN_LENGTH, validatePasswordLocally } from '../utils/passwordRules';
import { X, User, Lock, Settings as SettingsIcon } from 'lucide-react';
import { LanguageSelector } from './LanguageSelector';
import { useAppTranslation } from '../i18n/LocaleProvider';
import { translatedFieldErrors, translatedApiError } from '../i18n/errors';
import './ClientForms.css';

interface SettingsModalProps {
  onClose: () => void;
}

type FieldErrors = Record<string, string>;

const fieldErrorStyle = {
  color: 'var(--danger)',
  fontSize: '12px',
  marginTop: '5px',
} as const;

const bannerStyle = (kind: 'error' | 'success') =>
  ({
    padding: '10px 14px',
    borderRadius: '8px',
    background: kind === 'error' ? 'var(--danger-bg)' : 'var(--success-bg)',
    border: `1px solid ${kind === 'error' ? '#ebccc5' : '#bdcdb1'}`,
    color: kind === 'error' ? 'var(--danger)' : 'var(--emerald-accent)',
    fontSize: '13px',
  }) as const;

export const SettingsModal: React.FC<SettingsModalProps> = ({ onClose }) => {
  const { user, updateUser, login, logout } = useAuth();
  const { t } = useAppTranslation();
  const dismiss = useDismiss(onClose);
  const id = useId();

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
    <div className="dialog-overlay" {...dismiss}>
      <div
        role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} tabIndex={-1}
        className="dialog-panel client-dialog"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="dialog-header">
          <h2 id={`${id}-title`} className="dialog-title">
            <SettingsIcon size={20} /> {t('settings.title')}
          </h2>
          <button type="button" aria-label={t('common.close')} onClick={onClose} className="close-button">
            <X size={22} />
          </button>
        </div>

        <div className="dialog-body dialog-stack">

          <LanguageSelector description={t('settings.languageDescription')} />

          {/* Email (read-only) */}
          <div className="form-field">
            <label htmlFor={`${id}-email`}>
              {t('settingsModal.email')}
            </label>
            <input id={`${id}-email`} className="input-field" value={user.email} disabled />
            <div style={{ color: 'var(--text-muted)', fontSize: '12px', marginTop: '5px' }}>
              {t('settingsModal.emailIsYourSignInIdentifierAndCanTBeChangedHereYet')}
            </div>
          </div>

          {/* Profile form */}
          <form onSubmit={handleProfileSubmit} className="settings-section">
            <h3 className="form-section__title">
              <User size={16} /> {t('settingsModal.profile')}
            </h3>
            <div className="form-field">
              <label htmlFor={`${id}-name`}>
                {t('settingsModal.fullName')}
              </label>
              <input
                type="text"
                id={`${id}-name`}
                className="input-field"
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                aria-invalid={!!profileErrors.fullName}
              />
              {profileErrors.fullName && <div style={fieldErrorStyle}>{profileErrors.fullName}</div>}
            </div>

            {profileSuccess && <div style={bannerStyle('success')} role="status">{t('settingsModal.profileUpdated')}</div>}

            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button type="submit" className="btn-rose" disabled={profileSaving}>
                {profileSaving ? t('common.saving') :t('settingsModal.saveName')}
              </button>
            </div>
          </form>

          {/* Password form */}
          <form onSubmit={handlePasswordSubmit} className="settings-section">
            <h3 className="form-section__title">
              <Lock size={16} /> {t('settingsModal.changePassword')}
            </h3>

            <div className="form-field">
              <label htmlFor={`${id}-current-password`}>
                {t('settingsModal.currentPassword')}
              </label>
              <input
                type="password"
                id={`${id}-current-password`}
                autoComplete="current-password"
                className="input-field"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
                aria-invalid={!!passwordErrors.currentPassword}
              />
              {passwordErrors.currentPassword && <div style={fieldErrorStyle}>{passwordErrors.currentPassword}</div>}
            </div>

            <div className="form-field">
              <label htmlFor={`${id}-new-password`}>
                {t('settingsModal.newPassword')}
              </label>
              <input
                type="password"
                id={`${id}-new-password`}
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

            <div className="form-field">
              <label htmlFor={`${id}-confirm-password`}>
                {t('settingsModal.confirmNewPassword')}
              </label>
              <input
                type="password"
                id={`${id}-confirm-password`}
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
