import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, EMAIL_UNVERIFIED_EVENT } from '../services/api';
import { useAuth } from './AuthContext';

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

export type SendState = 'idle' | 'sending' | 'sent' | 'failed';

/**
 * Where an account stands with respect to email verification.
 *
 *  - `verified` — nothing to show.
 *  - `warning` — unconfirmed, but still inside a grace window. Only accounts
 *    that predate enforcement ever see this; a new registration is restricted
 *    from its first request. Renders as a banner over a working app.
 *  - `restricted` — the server is refusing this account. Renders as a wall.
 */
export type VerificationStanding = 'verified' | 'warning' | 'restricted';

/**
 * The shared brain behind [VerificationBanner] and [VerificationWall].
 *
 * Both screens need the same four things — the standing, a resend with a
 * cooldown, a re-check after the user clicks the link elsewhere, and honest
 * wording about which of those is in flight — and they need to agree. Two
 * copies of this logic would drift the first time one of them was touched, and
 * the failure mode is a user told two different stories about the same account
 * on two adjacent screens.
 *
 * Nothing here *decides* anything. The server is the only authority on whether
 * an account is restricted; the standing below is either what the profile
 * reported or what the server just answered to a refused request. A client
 * clock that is days out costs at most a slightly wrong countdown.
 */
export function useVerification() {
  const { user, updateUser } = useAuth();
  const [sendState, setSendState] = useState<SendState>('idle');
  const [cooldownUntil, setCooldownUntil] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const [serverRefused, setServerRefused] = useState(false);
  const [, forceTick] = useState(0);

  // A refusal from any request flips to the restricted state at once, without
  // waiting for the next token refresh to bring an updated profile. Whatever
  // the countdown said, the server has now spoken.
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

  const resend = useCallback(async () => {
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
  }, []);

  /**
   * Re-reads the profile after the user says they have clicked the link.
   *
   * The link opens in a different tab — often in a mail client, sometimes on a
   * different device — so this tab has no way to notice on its own. The
   * alternative is telling the user to reload, which works but looks like the
   * app failed to keep up.
   *
   * `GET /api/users/me` is deliberately outside the verification gate on the
   * server precisely so this call still works from behind the wall.
   */
  const recheck = useCallback(async () => {
    setRefreshing(true);
    try {
      const fresh = await api.getCurrentUser();
      updateUser(fresh);
      if (fresh.emailVerified) setServerRefused(false);
    } catch {
      // Leave the state as it is. A failed re-check says nothing about
      // whether the address was confirmed.
    } finally {
      setRefreshing(false);
    }
  }, [updateUser]);

  // `undefined` means the backend did not send the field — an older API, or a
  // profile shape we do not recognise. Say nothing rather than nag on a guess.
  const unverified = user != null && user.emailVerified === false;
  const deadline = unverified ? user.verificationDeadline ?? null : null;
  const past = deadline != null && new Date(deadline).getTime() <= Date.now();

  // No deadline and no refusal means this deployment has enforcement switched
  // off. The address is still worth confirming — password reset depends on it
  // — but there is nothing to warn about, so both screens stay quiet.
  let standing: VerificationStanding = 'verified';
  if (unverified && (serverRefused || past)) standing = 'restricted';
  else if (unverified && deadline != null) standing = 'warning';

  const coolingDown = cooldownUntil > Date.now();

  return {
    user,
    standing,
    deadline,
    daysLeft: deadline == null ? 0 : Math.max(0, Math.ceil((new Date(deadline).getTime() - Date.now()) / 86_400_000)),
    sendState,
    resend,
    coolingDown,
    secondsLeft: Math.ceil((cooldownUntil - Date.now()) / 1000),
    recheck,
    refreshing,
  };
}
