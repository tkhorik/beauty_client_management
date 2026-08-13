import { useEffect, useState } from 'react';
import { MailWarning, MailCheck, RefreshCw } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { api, ApiError, EMAIL_UNVERIFIED_EVENT } from '../services/api';

/**
 * How long the resend button stays disabled after a click.
 *
 * The backend allows 3 mail-sending calls per minute per IP. A salon behind one
 * office IP shares that budget, so the point of this cooldown is not to protect
 * the server but the user: without it, an impatient double-click spends two of
 * three attempts and the third person to try that minute gets a 429 for
 * something they did nothing wrong to cause.
 */
const RESEND_COOLDOWN_MS = 60_000;

type SendState = 'idle' | 'sending' | 'sent' | 'failed';

function daysUntil(deadline: string): number {
  const ms = new Date(deadline).getTime() - Date.now();
  return Math.max(0, Math.ceil(ms / 86_400_000));
}

/**
 * The standing notice that an account's address is unconfirmed.
 *
 * Rendered above everything an authenticated user can see — including the
 * organization onboarding screen, since creating an organization is one of the
 * blocked actions and a user stuck there with no explanation would have no idea
 * why.
 *
 * Two states, deliberately different in tone:
 *  - Inside the grace window: informational, with a countdown. The user can
 *    still do everything; this is a heads-up, not an obstacle.
 *  - After it: an explanation of why saving has stopped working.
 *
 * The component never *decides* anything — the server is the only authority on
 * whether a write is allowed. Its "restricted" state is either what the server
 * reported in the profile or what it just answered to a refused request. A
 * client clock that is days out is worth at most a slightly wrong countdown.
 */
export function VerificationBanner() {
  const { user, updateUser } = useAuth();
  const [sendState, setSendState] = useState<SendState>('idle');
  const [cooldownUntil, setCooldownUntil] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const [serverRefused, setServerRefused] = useState(false);
  const [, forceTick] = useState(0);

  // A refusal from any request flips the banner to its restricted state at
  // once, without waiting for the next token refresh to bring an updated
  // profile. Whatever the countdown said, the server has now spoken.
  useEffect(() => {
    const handler = () => setServerRefused(true);
    window.addEventListener(EMAIL_UNVERIFIED_EVENT, handler);
    return () => window.removeEventListener(EMAIL_UNVERIFIED_EVENT, handler);
  }, []);

  // Re-render while a cooldown is running so the button label counts down and
  // re-enables itself. Cheap: one interval, only while a cooldown is active.
  useEffect(() => {
    if (cooldownUntil <= Date.now()) return;
    const id = setInterval(() => forceTick(n => n + 1), 1000);
    return () => clearInterval(id);
  }, [cooldownUntil]);

  // `undefined` means the backend did not send the field — an older API, or a
  // profile shape we do not recognise. Say nothing rather than nag on a guess.
  if (!user || user.emailVerified !== false) return null;

  const deadline = user.verificationDeadline ?? null;
  const restricted = serverRefused || (deadline != null && new Date(deadline).getTime() <= Date.now());

  // No deadline and no refusal means this deployment has enforcement switched
  // off. The address is still worth confirming — password reset depends on it
  // — but there is nothing to warn about, so the banner stays quiet.
  if (!restricted && deadline == null) return null;

  const coolingDown = cooldownUntil > Date.now();
  const secondsLeft = Math.ceil((cooldownUntil - Date.now()) / 1000);

  async function resend() {
    setSendState('sending');
    try {
      await api.resendVerificationEmail();
      setSendState('sent');
      setCooldownUntil(Date.now() + RESEND_COOLDOWN_MS);
    } catch (err) {
      // A 429 is the interesting failure and deserves its own words: "try
      // again" is unhelpful advice for a limit that is measured in minutes.
      setSendState('failed');
      if (err instanceof ApiError && err.status === 429) {
        setCooldownUntil(Date.now() + RESEND_COOLDOWN_MS);
      }
    }
  }

  /**
   * Re-reads the profile after the user says they have clicked the link.
   *
   * The link opens in a different tab — often in a mail client, sometimes on a
   * different device — so this tab has no way to notice on its own. The
   * alternative is telling the user to reload, which works but looks like the
   * app failed to keep up.
   */
  async function recheck() {
    setRefreshing(true);
    try {
      const fresh = await api.getCurrentUser();
      updateUser(fresh);
      if (fresh.emailVerified) setServerRefused(false);
    } catch {
      // Leave the banner as it is. A failed re-check says nothing about
      // whether the address was confirmed.
    } finally {
      setRefreshing(false);
    }
  }

  const accent = restricted ? 'var(--rose-gold-primary)' : 'var(--text-muted)';

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
        borderLeft: `3px solid ${accent}`,
      }}
    >
      {restricted ? (
        <MailWarning size={20} color="var(--rose-gold-primary)" style={{ flexShrink: 0 }} />
      ) : (
        <MailCheck size={20} color={accent} style={{ flexShrink: 0 }} />
      )}

      <div style={{ flex: 1, minWidth: '260px' }}>
        <p style={{ margin: 0, fontWeight: 600 }}>
          {restricted
            ? 'Confirm your email to make changes'
            : `Confirm your email within ${daysUntil(deadline!)} day${daysUntil(deadline!) === 1 ? '' : 's'}`}
        </p>
        <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-muted)' }}>
          {restricted
            ? `Your account is read-only until ${user.email} is confirmed. You can still browse clients and visits.`
            : `We sent a link to ${user.email}. After that, saving changes needs a confirmed address.`}
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
