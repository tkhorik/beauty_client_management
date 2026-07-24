import { useState, type FormEvent } from 'react';
import { useAuth } from '../auth/AuthContext';
import { Sparkles } from 'lucide-react';

const API_BASE_URL = 'http://localhost:8080/api';

type Mode = 'login' | 'register';

export function LoginPage() {
  const { login } = useAuth();
  const [mode, setMode] = useState<Mode>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setLoading(true);

    try {
      const endpoint = mode === 'login'
        ? `${API_BASE_URL}/auth/login`
        : `${API_BASE_URL}/auth/register`;

      const body = mode === 'login'
        ? { email, password }
        : { email, password, fullName };

      const res = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });

      if (res.status === 401 || res.status === 400) {
        const data = await res.json().catch(() => ({}));
        setError(data.error || 'Invalid credentials.');
        return;
      }

      if (res.status === 409) {
        setError('An account with this email already exists.');
        return;
      }

      if (!res.ok) {
        setError('Something went wrong. Please try again.');
        return;
      }

      const data = await res.json();
      login(data.token);
    } catch {
      setError('Server could not be reached. Make sure the backend is running.');
    } finally {
      setLoading(false);
    }
  }

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

          {mode === 'register' && (
            <div>
              <label style={{ display: 'block', fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
                Full Name
              </label>
              <input
                type="text"
                className="glass-input"
                value={fullName}
                onChange={e => setFullName(e.target.value)}
                placeholder="Your name"
                required
                style={{ width: '100%' }}
              />
            </div>
          )}

          <div>
            <label style={{ display: 'block', fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
              Email
            </label>
            <input
              type="email"
              className="glass-input"
              value={email}
              onChange={e => setEmail(e.target.value)}
              placeholder="you@example.com"
              required
              style={{ width: '100%' }}
            />
          </div>

          <div>
            <label style={{ display: 'block', fontSize: '13px', color: 'var(--text-muted)', marginBottom: '6px' }}>
              Password
            </label>
            <input
              type="password"
              className="glass-input"
              value={password}
              onChange={e => setPassword(e.target.value)}
              placeholder="••••••••"
              required
              style={{ width: '100%' }}
            />
          </div>

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
                onClick={() => { setMode('register'); setError(''); }}
                style={{ background: 'none', border: 'none', color: 'var(--rose-gold-primary)', cursor: 'pointer', fontSize: '13px' }}
              >
                Create an account
              </button>
            </>
          ) : (
            <>
              Already have an account?{' '}
              <button
                onClick={() => { setMode('login'); setError(''); }}
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
