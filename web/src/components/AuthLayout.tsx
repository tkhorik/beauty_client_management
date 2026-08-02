import type { ReactNode } from 'react';
import { Sparkles } from 'lucide-react';

/**
 * The centred glass panel that `LoginPage` uses, extracted so the three pages
 * reachable without a session — forgot password, reset password and email
 * verification — look like the same product rather than three bare forms.
 *
 * `LoginPage` still carries its own copy of this markup: it is entangled with
 * its login/register mode toggle, and pulling it apart is a refactor with no
 * behavioural benefit and a real chance of breaking the one screen every user
 * sees.
 */
export function AuthLayout({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: ReactNode;
}) {
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
        <div style={{ textAlign: 'center', marginBottom: '28px' }}>
          <Sparkles size={36} color="var(--rose-gold-primary)" style={{ marginBottom: '12px' }} />
          <h1 className="text-gradient" style={{ fontSize: '24px', marginBottom: '6px' }}>
            {title}
          </h1>
          {subtitle && (
            <p style={{ color: 'var(--text-muted)', fontSize: '14px', lineHeight: 1.5 }}>
              {subtitle}
            </p>
          )}
        </div>
        {children}
      </div>
    </div>
  );
}

/** Inline error text, matching the style `LoginPage` uses for field errors. */
export const fieldErrorStyle = {
  color: '#e87c8a',
  fontSize: '12px',
  marginTop: '5px',
} as const;

/** The red banner `LoginPage` uses for form-level errors. */
export function ErrorBanner({ children }: { children: ReactNode }) {
  return (
    <div style={{
      padding: '10px 14px',
      borderRadius: '8px',
      background: 'rgba(220, 50, 80, 0.12)',
      border: '1px solid rgba(220, 50, 80, 0.3)',
      color: '#e87c8a',
      fontSize: '13px',
    }}>
      {children}
    </div>
  );
}

/** A text button styled as a link, for moving between the auth screens. */
export function LinkButton({ onClick, children }: { onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        background: 'none',
        border: 'none',
        color: 'var(--rose-gold-primary)',
        cursor: 'pointer',
        fontSize: '13px',
        padding: 0,
      }}
    >
      {children}
    </button>
  );
}
