import { useEffect, useState, type FormEvent } from 'react';
import { useAuth } from '../auth/AuthContext';
import { Sparkles, Eye, EyeOff } from 'lucide-react';
import { API_BASE_URL } from '../config';
import { AUTH_TRANSPORT_HEADERS } from '../auth/session';
import { PASSWORD_MIN_LENGTH, validatePasswordLocally } from '../utils/passwordRules';
import { FORGOT_PASSWORD_PATH, navigate } from '../auth/route';
import { LanguageSelector } from './LanguageSelector';
import { useAppTranslation, useLocale } from '../i18n/LocaleProvider';
import { effectiveAcceptLanguage } from '../i18n/store';
import { errorTranslationKey, translatedFieldErrors } from '../i18n/errors';
import { arrivedWithCreationLink, clearJoinSlug, pendingJoinSlug, stripDeepLinkParams } from '../auth/deepLinks';

type Mode = 'login' | 'register';

type FieldErrors = Record<string, string>;

export function LoginPage() {
  const { login } = useAuth();
  const { t } = useAppTranslation();
  const { preference, locale } = useLocale();
  // A "join my salon" link is almost always followed by someone without an
  // account yet, so it opens on the registration form.
  const [mode, setMode] = useState<Mode>(() => (pendingJoinSlug() ? 'register' : 'login'));
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

  const fieldErrorStyle = {
    color: '#e87c8a',
    fontSize: '12px',
    marginTop: '5px',
  } as const;

  return (
    <div style={{
      minHeight: '100vh',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: '24px',
    }}>
      <div className="glass-panel" style={{
        width: '100%',
        maxWidth: '420px',
        padding: '40px',
        borderRadius: '20px',
      }}>
        {/* Header */}
        <div style={{ textAlign: 'center', marginBottom: '32px' }}>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '12px' }}><LanguageSelector compact /></div>
          <Sparkles size={36} color="var(--rose-gold-primary)" style={{ marginBottom: '12px' }} />
          <h1 className="text-gradient" style={{ fontSize: '26px', marginBottom: '6px' }}>
            {t('common.appName')}
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '14px' }}>
            {mode === 'login' ? t('auth.signInAccount') : t('auth.createYourAccount')}
          </p>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>

          {isRegister && (
            <div>
              <label htmlFor="fullName" style={{ display: 'block', fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
                {t('auth.fullName')}
              </label>
              <input
                id="fullName"
                name="fullName"
                type="text"
                autoComplete="name"
                className="glass-input"
                value={fullName}
                onChange={e => setFullName(e.target.value)}
                placeholder={t('loginPage.yourName')}
                required
                aria-invalid={!!fieldErrors.fullName}
                style={{ width: '100%' }}
              />
              {fieldErrors.fullName && <div style={fieldErrorStyle}>{fieldErrors.fullName}</div>}
            </div>
          )}

          <div>
            <label htmlFor="email" style={{ display: 'block', fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
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
              className="glass-input"
              value={email}
              onChange={e => setEmail(e.target.value)}
              placeholder="you@example.com"
              required
              aria-invalid={!!fieldErrors.email}
              style={{ width: '100%' }}
            />
            {fieldErrors.email && <div style={fieldErrorStyle}>{fieldErrors.email}</div>}
          </div>

          <div>
            <label htmlFor="password" style={{ display: 'block', fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
              {t('auth.password')}
            </label>
            <div style={{ position: 'relative' }}>
              <input
                id="password"
                name="password"
                type={showPassword ? 'text' : 'password'}
                /* "new-password" tells the manager to generate/save a fresh
                   credential; "current-password" tells it to fill the saved
                   one. Using the wrong one breaks both behaviours. */
                autoComplete={isRegister ? 'new-password' : 'current-password'}
                minLength={isRegister ? PASSWORD_MIN_LENGTH : undefined}
                className="glass-input"
                value={password}
                onChange={e => setPassword(e.target.value)}
                placeholder="••••••••"
                required
                aria-invalid={!!fieldErrors.password}
                style={{ width: '100%', paddingRight: '42px' }}
              />
              <button
                type="button"
                onClick={() => setShowPassword(v => !v)}
                aria-label={showPassword ? t('auth.hidePassword') : t('auth.showPassword')}
                style={{
                  position: 'absolute',
                  right: '10px',
                  top: '50%',
                  transform: 'translateY(-50%)',
                  background: 'none',
                  border: 'none',
                  cursor: 'pointer',
                  color: 'var(--text-muted)',
                  display: 'flex',
                  alignItems: 'center',
                  padding: 0,
                }}
              >
                {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
            {fieldErrors.password ? (
              <div style={fieldErrorStyle}>{fieldErrors.password}</div>
            ) : isRegister ? (
              <div style={{ color: 'var(--text-muted)', fontSize: '12px', marginTop: '5px' }}>
                {t('password.guidance', { min: PASSWORD_MIN_LENGTH })}
              </div>
            ) : (
              // Sign-in only. Offering "forgot password" on the registration
              // form would point someone with no account at a flow that, by
              // design, tells them nothing about whether one exists.
              <div style={{ textAlign: 'right', marginTop: '6px' }}>
                <button
                  type="button"
                  onClick={() => navigate(FORGOT_PASSWORD_PATH)}
                  style={{ background: 'none', border: 'none', color: 'var(--rose-gold-primary)', cursor: 'pointer', fontSize: '12px', padding: 0 }}
                >
                  {t('auth.forgotPassword')}
                </button>
              </div>
            )}
          </div>

          {isRegister && (
            <div>
              <label htmlFor="confirmPassword" style={{ display: 'block', fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
              {t('auth.confirmPassword')}
              </label>
              <input
                id="confirmPassword"
                name="confirmPassword"
                type={showPassword ? 'text' : 'password'}
                autoComplete="new-password"
                className="glass-input"
                value={confirmPassword}
                onChange={e => setConfirmPassword(e.target.value)}
                placeholder="••••••••"
                required
                aria-invalid={!!fieldErrors.confirmPassword}
                style={{ width: '100%' }}
              />
              {fieldErrors.confirmPassword && <div style={fieldErrorStyle}>{fieldErrors.confirmPassword}</div>}
            </div>
          )}

          {isRegister && offerJoinField && (
            <div>
              <label htmlFor="organizationSlug" style={{ display: 'block', fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
                {t('loginPage.organizationHandle')}
              </label>
              <input
                id="organizationSlug"
                name="organizationSlug"
                type="text"
                autoComplete="off"
                autoCapitalize="none"
                spellCheck={false}
                className="glass-input"
                value={organizationSlug}
                onChange={e => setOrganizationSlug(e.target.value)}
                placeholder="my-salon"
                aria-invalid={!!fieldErrors.organizationSlug}
                aria-describedby="organizationSlugHint"
                style={{ width: '100%' }}
              />
              {fieldErrors.organizationSlug ? (
                <div style={fieldErrorStyle}>{fieldErrors.organizationSlug}</div>
              ) : (
                <div id="organizationSlugHint" style={{ color: 'var(--text-muted)', fontSize: '12px', marginTop: '5px' }}>
                  {t('loginPage.organizationHandleHint')}
                </div>
              )}
            </div>
          )}

          {error && (
            <div style={{
              padding: '10px 14px',
              borderRadius: '8px',
              background: 'rgba(220, 50, 80, 0.12)',
              border: '1px solid rgba(220, 50, 80, 0.3)',
              color: '#e87c8a',
              fontSize: '13px',
            }}>
              {error}
            </div>
          )}

          <button
            type="submit"
            className="btn-rose"
            disabled={loading}
            style={{ marginTop: '4px', opacity: loading ? 0.7 : 1, cursor: loading ? 'not-allowed' : 'pointer' }}
          >
            {loading ? t('auth.pleaseWait') : mode === 'login' ? t('auth.signIn') : t('auth.createAccount')}
          </button>
        </form>

        {/* Toggle mode */}
        <p style={{ textAlign: 'center', marginTop: '20px', fontSize: '13px', color: 'var(--text-muted)' }}>
          {mode === 'login' ? (
            <>
              {t('auth.firstTime')}{' '}
              <button
                onClick={() => switchMode('register')}
                style={{ background: 'none', border: 'none', color: 'var(--rose-gold-primary)', cursor: 'pointer', fontSize: '13px' }}
              >
                {t('auth.createAccount')}
              </button>
            </>
          ) : (
            <>
              {t('auth.alreadyAccount')}{' '}
              <button
                onClick={() => switchMode('login')}
                style={{ background: 'none', border: 'none', color: 'var(--rose-gold-primary)', cursor: 'pointer', fontSize: '13px' }}
              >
                {t('auth.signIn')}
              </button>
            </>
          )}
        </p>
      </div>
    </div>
  );
}
