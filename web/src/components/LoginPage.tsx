import { useEffect, useState, type FormEvent } from 'react';
import { useAuth } from '../auth/useAuth';
import { Sparkles, Eye, EyeOff, ArrowLeft } from 'lucide-react';
import { API_BASE_URL } from '../config';
import { AUTH_TRANSPORT_HEADERS } from '../auth/session';
import { PASSWORD_MIN_LENGTH, validatePasswordLocally } from '../utils/passwordRules';
import { FORGOT_PASSWORD_PATH, navigate } from '../auth/route';
import { ThemeSelector } from './ThemeSelector';
import { LanguageSelector } from './LanguageSelector';
import './AuthScreens.css';
import { useAppTranslation, useLocale } from '../i18n/useLocale';
import { effectiveAcceptLanguage } from '../i18n/store';
import { errorTranslationKey, translatedFieldErrors } from '../i18n/errors';
import { arrivedWithCreationLink, clearJoinSlug, pendingInviteToken, pendingJoinSlug, stripDeepLinkParams } from '../auth/deepLinks';

export type LoginMode = 'login' | 'register';

type Mode = LoginMode;

type FieldErrors = Record<string, string>;

export function LoginPage({ initialMode = 'login', onBack }: { initialMode?: LoginMode; onBack?: () => void } = {}) {
  const { login } = useAuth();
  const { t } = useAppTranslation();
  const { preference, locale } = useLocale();
  // A "join my salon" link is almost always followed by someone without an
  // account yet, so it opens on the registration form.
  const [mode, setMode] = useState<Mode>(() => (pendingJoinSlug() ? 'register' : initialMode));
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [organizationSlug, setOrganizationSlug] = useState(() => pendingJoinSlug() ?? '');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setError('');
    setFieldErrors({});
  }, [locale]);

  useEffect(() => { stripDeepLinkParams(); }, []);

  // Someone who arrived with an organization-creation link is about to create
  // their own organization; offering "request access to someone else's" on
  // the same form would only confuse that.
  const offerJoinField = !arrivedWithCreationLink;

  const isRegister = mode === 'register';

  function switchMode(next: Mode) {
    setMode(next);
    setError('');
    setFieldErrors({});
    setConfirmPassword('');
  }

  /** Mirrors the backend rules so the common mistakes never leave the browser. */
  function validateLocally(): FieldErrors {
    if (!isRegister) return {};
    const errors: FieldErrors = {};

    if (!fullName.trim()) {
      errors.fullName = t('auth.nameRequired');
    }
    const passwordError = validatePasswordLocally(password);
    if (passwordError) {
      errors.password = passwordError;
    }
    // Checked in the browser only: the server never sees the confirmation
    // field, and a typo here would otherwise lock the user out of an account
    // they just created, recoverable only by going through the reset flow.
    if (password !== confirmPassword) {
      errors.confirmPassword = t('auth.passwordsMismatch');
    }
    return errors;
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setFieldErrors({});

    const localErrors = validateLocally();
    if (Object.keys(localErrors).length > 0) {
      setFieldErrors(localErrors);
      return;
    }

    setLoading(true);

    try {
      const endpoint = isRegister
        ? `${API_BASE_URL}/auth/register`
        : `${API_BASE_URL}/auth/login`;

      const requestedSlug = offerJoinField ? organizationSlug.trim().toLowerCase() : '';
      const body = isRegister
        ? { email, password, fullName, languagePreference: preference, ...(requestedSlug ? { organizationSlug: requestedSlug } : {}) }
        : { email, password };

      const res = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept-Language': effectiveAcceptLanguage(preference), ...AUTH_TRANSPORT_HEADERS },
        // Required for the browser to accept the httpOnly refresh cookie the
        // backend sets on a successful login.
        credentials: 'include',
        body: JSON.stringify(body),
      });

      // The register endpoint returns per-field messages; render them next to
      // the inputs rather than collapsing them into one banner.
      if (res.status === 400) {
        const data = await res.json().catch(() => ({}));
        if (data.fieldErrors && typeof data.fieldErrors === 'object') {
          setFieldErrors(translatedFieldErrors(data));
        } else if (data.errors && typeof data.errors === 'object') {
          setFieldErrors(Object.fromEntries(Object.keys(data.errors).map(field => [field, t('errors.VALIDATION_ERROR')])));
        } else {
          setError(data.error ? t(errorTranslationKey({ body: data })) : t('auth.checkDetails'));
        }
        return;
      }

      if (res.status === 401) {
        const data = await res.json().catch(() => ({}));
        setError(data.code ? t(errorTranslationKey({ body: data })) : t('auth.invalidCredentials'));
        return;
      }

      if (res.status === 409) {
        setFieldErrors({ email: t('auth.duplicateEmail') });
        return;
      }

      if (res.status === 429) {
        setError(t('auth.tooManyAttempts'));
        return;
      }

      if (!res.ok) {
        setError(t('common.error'));
        return;
      }

      const data = await res.json();
      // The request is filed; the onboarding screen shows it as pending and
      // must not offer to send it again.
      if (isRegister && requestedSlug) clearJoinSlug();
      login(data.token, data.user);
    } catch {
      setError(t('common.network'));
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="auth-screen">
      <aside className="auth-screen__aside" aria-hidden="true"><div className="auth-screen__aside-inner"><div className="auth-screen__brand"><Sparkles size={22} /><span>Aura</span></div><p>{t('landingPage.heroTitle')}</p><div className="auth-screen__aside-note">{t('landingPage.studioCaption')}</div></div></aside>
      <section className="auth-screen__content">
      <div className="auth-card">
        {/* Header */}
        <header className="auth-card__header">
          <div className="auth-card__top" style={{ justifyContent: onBack ? 'space-between' : 'flex-end' }}>
            {onBack && (
              <button
                type="button"
                onClick={onBack}
                className="auth-quiet-button"
              >
                <ArrowLeft size={14} aria-hidden="true" /> {t('loginPage.back')}
              </button>
            )}
            <div className="display-preferences"><ThemeSelector compact /><LanguageSelector compact /></div>
          </div>
          <div className="auth-card__mark" aria-hidden="true"><Sparkles size={24} /></div>
          <h1>
            {t('common.appName')}
          </h1>
          <p>
            {mode === 'login' ? t('auth.signInAccount') : t('auth.createYourAccount')}
          </p>
          {pendingInviteToken() && (
            <p role="status" className="auth-alert auth-alert--info">
              {t('loginPage.inviteNotice')}
            </p>
          )}
        </header>

        {/* Form */}
        <form onSubmit={handleSubmit} className="auth-form">

          {isRegister && (
            <div className="auth-field">
              <label htmlFor="fullName">
                {t('auth.fullName')}
              </label>
              <input
                id="fullName"
                name="fullName"
                type="text"
                autoComplete="name"
                className="input-field"
                value={fullName}
                onChange={e => setFullName(e.target.value)}
                placeholder={t('loginPage.yourName')}
                required
                aria-invalid={!!fieldErrors.fullName}
                style={{ width: '100%' }}
              />
              {fieldErrors.fullName && <div className="auth-field-error">{fieldErrors.fullName}</div>}
            </div>
          )}

          <div className="auth-field">
            <label htmlFor="email">
              {t('auth.email')}
            </label>
            <input
              id="email"
              name="email"
              type="email"
              /* Without an autocomplete hint, password managers cannot reliably
                 offer to save or fill this pair — which is the single biggest
                 practical driver of password reuse. */
              autoComplete="email"
              className="input-field"
              value={email}
              onChange={e => setEmail(e.target.value)}
              placeholder="you@example.com"
              required
              aria-invalid={!!fieldErrors.email}
              style={{ width: '100%' }}
            />
            {fieldErrors.email && <div className="auth-field-error">{fieldErrors.email}</div>}
          </div>

          <div className="auth-field">
            <label htmlFor="password">
              {t('auth.password')}
            </label>
            <div className="auth-password">
              <input
                id="password"
                name="password"
                type={showPassword ? 'text' : 'password'}
                /* "new-password" tells the manager to generate/save a fresh
                   credential; "current-password" tells it to fill the saved
                   one. Using the wrong one breaks both behaviours. */
                autoComplete={isRegister ? 'new-password' : 'current-password'}
                minLength={isRegister ? PASSWORD_MIN_LENGTH : undefined}
                className="input-field"
                value={password}
                onChange={e => setPassword(e.target.value)}
                placeholder="••••••••"
                required
                aria-invalid={!!fieldErrors.password}
                style={{ width: '100%' }}
              />
              <button
                type="button"
                onClick={() => setShowPassword(v => !v)}
                aria-label={showPassword ? t('auth.hidePassword') : t('auth.showPassword')}
                className="auth-icon-button"
              >
                {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
            {fieldErrors.password ? (
              <div className="auth-field-error">{fieldErrors.password}</div>
            ) : isRegister ? (
              <div className="auth-hint">
                {t('password.guidance', { min: PASSWORD_MIN_LENGTH })}
              </div>
            ) : (
              // Sign-in only. Offering "forgot password" on the registration
              // form would point someone with no account at a flow that, by
              // design, tells them nothing about whether one exists.
              <div style={{ textAlign: 'right' }}>
                <button
                  type="button"
                  onClick={() => navigate(FORGOT_PASSWORD_PATH)}
                  className="auth-link"
                >
                  {t('auth.forgotPassword')}
                </button>
              </div>
            )}
          </div>

          {isRegister && (
            <div className="auth-field">
              <label htmlFor="confirmPassword">
              {t('auth.confirmPassword')}
              </label>
              <input
                id="confirmPassword"
                name="confirmPassword"
                type={showPassword ? 'text' : 'password'}
                autoComplete="new-password"
                className="input-field"
                value={confirmPassword}
                onChange={e => setConfirmPassword(e.target.value)}
                placeholder="••••••••"
                required
                aria-invalid={!!fieldErrors.confirmPassword}
                style={{ width: '100%' }}
              />
              {fieldErrors.confirmPassword && <div className="auth-field-error">{fieldErrors.confirmPassword}</div>}
            </div>
          )}

          {isRegister && offerJoinField && (
            <div className="auth-field">
              <label htmlFor="organizationSlug">
                {t('loginPage.organizationHandle')}
              </label>
              <input
                id="organizationSlug"
                name="organizationSlug"
                type="text"
                autoComplete="off"
                autoCapitalize="none"
                spellCheck={false}
                className="input-field"
                value={organizationSlug}
                onChange={e => setOrganizationSlug(e.target.value)}
                placeholder="my-salon"
                aria-invalid={!!fieldErrors.organizationSlug}
                aria-describedby="organizationSlugHint"
                style={{ width: '100%' }}
              />
              {fieldErrors.organizationSlug ? (
                <div className="auth-field-error">{fieldErrors.organizationSlug}</div>
              ) : (
                <div id="organizationSlugHint" className="auth-hint">
                  {t('loginPage.organizationHandleHint')}
                </div>
              )}
            </div>
          )}

          {error && (
            <div className="auth-alert auth-alert--error" role="alert">
              {error}
            </div>
          )}

          <button
            type="submit"
            className="btn-rose"
            disabled={loading}
          style={{ opacity: loading ? 0.7 : 1 }}
          >
            {loading ? t('auth.pleaseWait') : mode === 'login' ? t('auth.signIn') : t('auth.createAccount')}
          </button>
        </form>

        {/* Toggle mode */}
        <p className="auth-form-footer">
          {mode === 'login' ? (
            <>
              {t('auth.firstTime')}{' '}
              <button
                onClick={() => switchMode('register')}
                className="auth-link"
              >
                {t('auth.createAccount')}
              </button>
            </>
          ) : (
            <>
              {t('auth.alreadyAccount')}{' '}
              <button
                onClick={() => switchMode('login')}
                className="auth-link"
              >
                {t('auth.signIn')}
              </button>
            </>
          )}
        </p>
      </div>
      </section>
    </main>
  );
}
