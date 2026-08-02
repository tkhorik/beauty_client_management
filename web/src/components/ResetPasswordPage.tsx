import { useEffect, useState, type FormEvent } from 'react';
import { Eye, EyeOff, ShieldCheck } from 'lucide-react';
import { API_BASE_URL } from '../config';
import { FORGOT_PASSWORD_PATH, navigate, stripQueryString } from '../auth/route';
import { PASSWORD_MIN_LENGTH, validatePasswordLocally } from '../utils/passwordRules';
import { AuthLayout, ErrorBanner, LinkButton, fieldErrorStyle } from './AuthLayout';

type FieldErrors = Record<string, string>;

/**
 * The page the emailed reset link lands on.
 *
 * The token arrives in the query string — see `AccountMailer.sendPasswordReset`,
 * which points this link at the web app rather than the API precisely because
 * the user has to type something. It is captured once as a prop and then
 * stripped from the address bar; see [stripQueryString] for why.
 *
 * Password rules come from `utils/passwordRules.ts`, the same module
 * `LoginPage` and `SettingsModal` use, so this form cannot drift from
 * `Validation.kt` independently of the others. The server remains the
 * authority: this only saves a round trip.
 *
 * On success the backend issues **no session** — deliberately, so the new
 * password is proved by using it — hence the redirect to the sign-in screen
 * rather than into the app.
 */
export function ResetPasswordPage({ token }: { token: string }) {
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  // The token is already held in component state by the time this runs, so
  // clearing the URL costs nothing and keeps a live credential out of the
  // address bar, browser history and outbound Referer headers.
  useEffect(() => {
    if (token) stripQueryString();
  }, [token]);

  /**
   * Rendered for a link with no token at all — a hand-typed path, or one a
   * mail client truncated. Identical in wording to the message the server
   * returns for a token it rejects, so the two cannot be told apart.
   */
  if (!token) {
    return (
      <AuthLayout
        title="This link isn't usable"
        subtitle="This reset link is invalid or has expired. Reset links can only be used once, and each new one cancels the last."
      >
        <button type="button" className="btn-rose" onClick={() => navigate(FORGOT_PASSWORD_PATH)}>
          Request a new link
        </button>
      </AuthLayout>
    );
  }

  if (done) {
    return (
      <AuthLayout title="Password changed">
        <div style={{ textAlign: 'center' }}>
          <ShieldCheck size={40} color="var(--rose-gold-primary)" style={{ marginBottom: '16px' }} />
          <p style={{ color: 'var(--text-muted)', fontSize: '14px', lineHeight: 1.6 }}>
            Your password has been changed and you've been signed out everywhere else.
            Sign in with your new password to continue.
          </p>
          <button
            type="button"
            className="btn-rose"
            onClick={() => navigate('/')}
            style={{ marginTop: '24px' }}
          >
            Sign in
          </button>
        </div>
      </AuthLayout>
    );
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setFieldErrors({});

    const localErrors: FieldErrors = {};
    const passwordError = validatePasswordLocally(password);
    if (passwordError) localErrors.newPassword = passwordError;
    // Checked here only — the server never sees the confirmation field, and a
    // typo would lock the user out of the account they are trying to recover,
    // with their one usable link already spent.
    if (password !== confirmPassword) localErrors.confirmPassword = 'Passwords do not match.';

    if (Object.keys(localErrors).length > 0) {
      setFieldErrors(localErrors);
      return;
    }

    setLoading(true);
    try {
      const res = await fetch(`${API_BASE_URL}/auth/reset-password`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, newPassword: password }),
      });

      if (res.ok) {
        setDone(true);
        return;
      }

      const data = await res.json().catch(() => ({}));

      if (res.status === 400) {
        // Two different 400s share this branch. A field-level `errors` object
        // means the password was rejected and the token is *still valid* —
        // the backend validates before redeeming exactly so a short password
        // doesn't burn the link — so the form stays open for another attempt.
        // A flat `error` means the token itself was refused, and retrying is
        // pointless.
        if (data.errors && typeof data.errors === 'object') {
          setFieldErrors(data.errors as FieldErrors);
        } else {
          setError(data.error || 'This reset link is invalid or has expired. Please request a new one.');
        }
        return;
      }

      if (res.status === 429) {
        setError('Too many attempts. Please wait a minute and try again.');
        return;
      }

      setError('Something went wrong. Please try again.');
    } catch {
      setError('Server could not be reached. Please check your connection and try again.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <AuthLayout
      title="Choose a new password"
      subtitle="Setting a new password signs you out on every device."
    >
      <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
        <div>
          <label htmlFor="newPassword" style={{ display: 'block', fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
            New password
          </label>
          <div style={{ position: 'relative' }}>
            <input
              id="newPassword"
              name="newPassword"
              type={showPassword ? 'text' : 'password'}
              autoComplete="new-password"
              minLength={PASSWORD_MIN_LENGTH}
              className="glass-input"
              value={password}
              onChange={e => setPassword(e.target.value)}
              placeholder="••••••••"
              required
              autoFocus
              aria-invalid={!!fieldErrors.newPassword}
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
          {fieldErrors.newPassword ? (
            <div style={fieldErrorStyle}>{fieldErrors.newPassword}</div>
          ) : (
            <div style={{ color: 'var(--text-muted)', fontSize: '12px', marginTop: '5px' }}>
              At least {PASSWORD_MIN_LENGTH} characters. A memorable phrase beats a short, complex password.
            </div>
          )}
        </div>

        <div>
          <label htmlFor="confirmPassword" style={{ display: 'block', fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
            Confirm new password
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

        {error && (
          <>
            <ErrorBanner>{error}</ErrorBanner>
            <div style={{ textAlign: 'center', fontSize: '13px' }}>
              <LinkButton onClick={() => navigate(FORGOT_PASSWORD_PATH)}>Request a new link</LinkButton>
            </div>
          </>
        )}

        <button
          type="submit"
          className="btn-rose"
          disabled={loading}
          style={{ marginTop: '4px', opacity: loading ? 0.7 : 1, cursor: loading ? 'not-allowed' : 'pointer' }}
        >
          {loading ? 'Please wait…' : 'Set new password'}
        </button>
      </form>
    </AuthLayout>
  );
}
