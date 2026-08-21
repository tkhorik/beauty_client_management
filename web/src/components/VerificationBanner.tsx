import { MailCheck, RefreshCw } from 'lucide-react';
import { useVerification } from '../auth/useVerification';

/**
 * The standing notice that an account's address is unconfirmed, shown *over a
 * working app*.
 *
 * Only one kind of account ever sees this: one that existed before enforcement
 * was switched on and is still inside its grace window. A registration made
 * under the rule is restricted from its first request and gets
 * [VerificationWall] instead — this banner would be dishonest there, since it
 * sits above an app the user cannot actually use.
 *
 * The tone is deliberately informational rather than obstructive. Nothing is
 * blocked yet; the user is being given notice and the means to act on it, with
 * a countdown so the deadline is a fact rather than an unspecified threat.
 *
 * All the behaviour lives in [useVerification], shared with the wall so the two
 * screens cannot start telling the user different stories.
 */
export function VerificationBanner() {
  const {
    user,
    standing,
    daysLeft,
    sendState,
    resend,
    coolingDown,
    secondsLeft,
    recheck,
    refreshing,
  } = useVerification();

  // 'restricted' is the wall's job, and 'verified' has nothing to say.
  if (standing !== 'warning' || !user) return null;

  return (
    <div
      className="glass-panel"
      role="status"
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '14px',
        flexWrap: 'wrap',
        padding: '14px 18px',
        marginBottom: '20px',
        borderRadius: '14px',
        borderLeft: '3px solid var(--text-muted)',
      }}
    >
      <MailCheck size={20} color="var(--text-muted)" style={{ flexShrink: 0 }} />

      <div style={{ flex: 1, minWidth: '260px' }}>
        <p style={{ margin: 0, fontWeight: 600 }}>
          Confirm your email within {daysLeft} day{daysLeft === 1 ? '' : 's'}
        </p>
        <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-muted)' }}>
          We sent a link to {user.email}. Check your spam folder if it hasn't
          arrived. After {daysLeft} day{daysLeft === 1 ? '' : 's'}, you'll need a
          confirmed address to keep using Aura.
        </p>
        {sendState === 'sent' && (
          <p style={{ margin: '6px 0 0', fontSize: '13px', color: 'var(--rose-gold-primary)' }}>
            Link sent. Check your inbox, and your spam folder.
          </p>
        )}
        {sendState === 'failed' && (
          <p style={{ margin: '6px 0 0', fontSize: '13px', color: 'var(--rose-gold-primary)' }}>
            {coolingDown
              ? 'Too many requests just now — try again in a minute.'
              : "Couldn't send the link. Check your connection and try again."}
          </p>
        )}
      </div>

      <div style={{ display: 'flex', gap: '8px', flexShrink: 0 }}>
        <button
          className="btn-rose"
          onClick={recheck}
          disabled={refreshing}
          title="Already clicked the link? Check again."
          style={{ opacity: refreshing ? 0.6 : 1 }}
        >
          <RefreshCw size={16} /> {refreshing ? 'Checking…' : "I've confirmed"}
        </button>
        <button
          className="btn-rose"
          onClick={resend}
          disabled={sendState === 'sending' || coolingDown}
          style={{ opacity: sendState === 'sending' || coolingDown ? 0.6 : 1 }}
        >
          {sendState === 'sending'
            ? 'Sending…'
            : coolingDown
              ? `Resend in ${secondsLeft}s`
              : 'Resend link'}
        </button>
      </div>
    </div>
  );
}
