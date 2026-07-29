import { useState, type FormEvent } from 'react';
import { useAuth } from '../auth/AuthContext';
import { Sparkles, Eye, EyeOff } from 'lucide-react';
import { API_BASE_URL } from '../config';
import { AUTH_TRANSPORT_HEADERS } from '../auth/session';
import { PASSWORD_MIN_LENGTH, validatePasswordLocally } from '../utils/passwordRules';

type Mode = 'login' | 'register';

type FieldErrors = Record<string, string>;

export function LoginPage() {
  const { login } = useAuth();
  const [mode, setMode] = useState<Mode>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [loading, setLoading] = useState(false);

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
      errors.fullName = 'Name is required.';
    }
    const passwordError = validatePasswordLocally(password);
    if (passwordError) {
      errors.password = passwordError;
    }
    // Checked in the browser only: the server never sees the confirmation
    // field, and a typo here would otherwise lock the user out of an account
    // they just created and cannot yet reset.
    if (password !== confirmPassword) {
      errors.confirmPassword = 'Passwords do not match.';
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

      const body = isRegister
        ? { email, password, fullName }
        : { email, password };

      const res = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...AUTH_TRANSPORT_HEADERS },
        // Required for the browser to accept the httpOnly refresh cookie the
        // backend sets on a successful login.
        credentials: 'include',
        body: JSON.stringify(body),
      });

      // The register endpoint returns per-field messages; render them next to
      // the inputs rather than collapsing them into one banner.
      if (res.status === 400) {
        const data = await res.json().catch(() => ({}));
        if (data.errors && typeof data.errors === 'object') {
          setFieldErrors(data.errors as FieldErrors);
        } else {
          setError(data.error || 'Please check the details you entered.');
        }
        return;
      }

      if (res.status === 401) {
        const data = await res.json().catch(() => ({}));
        setError(data.error || 'Invalid credentials.');
        return;
      }

      if (res.status === 409) {
        setFieldErrors({ email: 'An account with this email already exists.' });
        return;
      }

      if (res.status === 429) {
        setError('Too many attempts. Please wait a moment and try again.');
        return;
      }

      if (!res.ok) {
        setError('Something went wrong. Please try again.');
        return;
      }

      const data = await res.json();
      login(data.token, data.user);
    } catch {
      setError('Server could not be reached. Make sure the backend is running.');
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
          <Sparkles size={36} color="var(--rose-gold-primary)" style={{ marginBottom: '12px' }} />
          <h1 className="text-gradient" style={{ fontSize: '26px', marginBottom: '6px' }}>
            Aura Beauty Log
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '14px' }}>
            {mode === 'login' ? 'Sign in to your account' : 'Create your account'}
          </p>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>

          {isRegister && (
            <div>
              <label htmlFor="fullName" style={{ display: 'block', fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
                Full Name
              </label>
              <input
                id="fullName"
                name="fullName"
                type="text"
                autoComplete="name"
                className="glass-input"
                value={fullName}
                onChange={e => setFullName(e.target.value)}
                placeholder="Your name"
                required
                aria-invalid={!!fieldErrors.fullName}
                style={{ width: '100%' }}
              />
              {fieldErrors.fullName && <div style={fieldErrorStyle}>{fieldErrors.fullName}</div>}
            </div>
          )}

          <div>
            <label htmlFor="email" style={{ display: 'block', fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
              Email
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
              Password
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
                aria-label={showPassword ? 'Hide password' : 'Show password'}
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
                At least {PASSWORD_MIN_LENGTH} characters. A memorable phrase beats a short, complex password.
              </div>
            ) : null}
          </div>

          {isRegister && (
            <div>
              <label htmlFor="confirmPassword" style={{ display: 'block', fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
                Confirm Password
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
            {loading ? 'Please wait…' : mode === 'login' ? 'Sign In' : 'Create Account'}
          </button>
        </form>

        {/* Toggle mode */}
        <p style={{ textAlign: 'center', marginTop: '20px', fontSize: '13px', color: 'var(--text-muted)' }}>
          {mode === 'login' ? (
            <>
              First time here?{' '}
              <button
                onClick={() => switchMode('register')}
                style={{ background: 'none', border: 'none', color: 'var(--rose-gold-primary)', cursor: 'pointer', fontSize: '13px' }}
              >
                Create an account
              </button>
            </>
          ) : (
            <>
              Already have an account?{' '}
              <button
                onClick={() => switchMode('login')}
                style={{ background: 'none', border: 'none', color: 'var(--rose-gold-primary)', cursor: 'pointer', fontSize: '13px' }}
              >
                Sign in
              </button>
            </>
          )}
        </p>
      </div>
    </div>
  );
}
