import { useAppTranslation } from '../i18n/LocaleProvider';
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
  const { t } = useAppTranslation();
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
          {t('verificationBanner.deadline', { count: daysLeft })}
        </p>
        <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-muted)' }}>
          {t('verificationBanner.description', { email: user.email, days: t('counts.days', { count: daysLeft }) })}
        </p>
        {sendState === 'sent' && (
          <p style={{ margin: '6px 0 0', fontSize: '13px', color: 'var(--rose-gold-primary)' }}>
            {t('verificationBanner.linkSentCheckYourInboxAndYourSpamFolder')}
          </p>
        )}
        {sendState === 'failed' && (
          <p style={{ margin: '6px 0 0', fontSize: '13px', color: 'var(--rose-gold-primary)' }}>
            {coolingDown
              ?t('verificationBanner.tooManyRequestsJustNowTryAgainInAMinute')
              :t('verificationBanner.couldnTSendTheLinkCheckYourConnectionAndTryAgain')}
          </p>
        )}
      </div>

      <div style={{ display: 'flex', gap: '8px', flexShrink: 0 }}>
        <button
          className="btn-rose"
          onClick={recheck}
          disabled={refreshing}
          title={t('verificationBanner.alreadyClickedTheLinkCheckAgain')}
          style={{ opacity: refreshing ? 0.6 : 1 }}
        >
          <RefreshCw size={16} /> {refreshing ?t('verificationBanner.checking') :t('verificationBanner.iVeConfirmed')}
        </button>
        <button
          className="btn-rose"
          onClick={resend}
          disabled={sendState === 'sending' || coolingDown}
          style={{ opacity: sendState === 'sending' || coolingDown ? 0.6 : 1 }}
        >
          {sendState === 'sending'
            ?t('verificationBanner.sending')
            : coolingDown
              ? t('verification.resendIn', { seconds: secondsLeft })
              :t('verificationBanner.resendLink')}
        </button>
      </div>
    </div>
  );
}
