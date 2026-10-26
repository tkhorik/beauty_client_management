import { useAppTranslation } from '../i18n/useLocale';
import type { ReactNode } from 'react';
import { MailWarning, RefreshCw, Send, LogOut } from 'lucide-react';
import { useAuth } from '../auth/useAuth';
import { useVerification } from '../auth/useVerification';
import { AuthLayout } from './AuthLayout';

/**
 * What an unverified account sees instead of the application.
 *
 * The server refuses every organization-scoped request from a restricted
 * account, reads included, so there is no version of the app to render behind
 * this screen — an app shell here would be a grid of failed requests with a
 * banner on top. Showing the reason once, in full, is both kinder and more
 * honest than letting the user discover it one 403 at a time.
 *
 * Three things a person in this state actually needs, and nothing else:
 *  1. **Which address.** Overwhelmingly the reason the mail never arrived is
 *     that it went somewhere else, and the address is the only clue that says
 *     so. It is shown verbatim, typos included.
 *  2. **Check your spam folder.** The second most common cause, and the one
 *     the user can fix without any help from us.
 *  3. **A way to act** — resend the link, re-check after clicking it in
 *     another tab, or sign out (the only escape when the address itself is
 *     wrong and the account has to be abandoned).
 *
 * Every button here calls a route that is deliberately outside the
 * verification gate on the server; see `plugins/OrgAccess`. If one of them
 * ever starts answering 403, this screen becomes a dead end, which is why
 * `EmailVerificationEnforcementTest` pins each of those routes individually.
 */
export function VerificationWall() {
  const { t } = useAppTranslation();
  const { logout } = useAuth();
  const {
    user,
    sendState,
    resend,
    coolingDown,
    secondsLeft,
    recheck,
    refreshing,
  } = useVerification();

  if (!user) return null;

  return (
    <AuthLayout
      title={t('verificationWall.confirmYourEmail')}
      subtitle={t('verificationWall.yourAccountIsReadyItJustNeedsAConfirmedEmailAddressBeforeYouCanU')}
    >
      <div className="auth-content-center">
        <MailWarning size={40} className="auth-status-icon" />

        <p style={{ overflowWrap: 'anywhere' }}>
          {t('verificationWall.sentDescription', { email: user.email })}
        </p>
        <p className="auth-hint">
          {t('verificationWall.spamDescription')}
        </p>

        {sendState === 'sent' && (
          <p
            role="status"
            className="auth-alert auth-alert--success"
          >
            {t('verificationWall.newLinkSentItCanTakeAMinuteToArrive')}
          </p>
        )}
        {sendState === 'failed' && (
          <p
            role="status"
            className="auth-alert auth-alert--error"
          >
            {coolingDown
              ?t('verificationWall.tooManyRequestsJustNowTryAgainInAMinute')
              :t('verificationWall.couldnTSendTheLinkCheckYourConnectionAndTryAgain')}
          </p>
        )}

        <div className="auth-actions-stack">
          {/*
            The primary action is the re-check, not the resend. Most people
            reaching this screen have already received the mail and are coming
            back after clicking it in another tab; offering "send another one"
            first would have them spend a rate-limit slot solving a problem
            they no longer have.
          */}
          <button
            type="button"
            className="btn-rose"
            onClick={recheck}
            disabled={refreshing}
            style={{ opacity: refreshing ? 0.6 : 1 }}
          >
            <RefreshCw size={16} /> {refreshing ?t('verificationWall.checking') :t('verificationWall.iVeConfirmedMyEmail')}
          </button>

          <button
            type="button"
            className="btn-rose"
            onClick={resend}
            disabled={sendState === 'sending' || coolingDown}
            style={{ opacity: sendState === 'sending' || coolingDown ? 0.6 : 1 }}
          >
            <Send size={16} />{' '}
            {sendState === 'sending'
              ?t('verificationWall.sending')
              : coolingDown
                ? t('verification.resendIn', { seconds: secondsLeft })
                :t('verificationWall.sendTheLinkAgain')}
          </button>
        </div>

        {/*
          The way out for someone who registered with a typo. Without it the
          only remedy is clearing site data, and the address in the panel above
          is exactly the information that tells them they need it.
        */}
        <button
          type="button"
          onClick={logout}
          className="auth-quiet-button"
          style={{ marginTop: '20px' }}
        >
          <LogOut size={14} /> {t('verificationWall.wrongAddressSignOut')}
        </button>
      </div>
    </AuthLayout>
  );
}

/**
 * Stands between a valid session and everything organization-scoped.
 *
 * Placed *outside* `OrgProvider` on purpose, exactly as the session-less pages
 * are placed outside both providers in `main.tsx`. `OrgProvider` fetches the
 * organization list the moment it mounts, and that route is behind the same
 * gate as the rest of the API: mounting it behind the wall would fire a
 * request that can only ever 403, and `services/api.ts` would emit an
 * `EMAIL_UNVERIFIED` event for a refusal the user has already been told about.
 *
 * Passes straight through when there is no session — `App` renders the login
 * page in that case — and when the account is verified or merely inside its
 * grace window, which `VerificationBanner` handles instead.
 */
export function VerificationGate({ children }: { children: ReactNode }) {
  const { token, initialising } = useAuth();
  const { standing } = useVerification();

  // Same reasoning as `App`'s own guard: during the refresh-cookie exchange
  // there is briefly no token and no profile, and deciding anything from that
  // would flash the wrong screen at a signed-in user.
  if (initialising) return null;
  if (!token) return <>{children}</>;

  return standing === 'restricted' ? <VerificationWall /> : <>{children}</>;
}
