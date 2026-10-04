import { useAppTranslation } from '../i18n/LocaleProvider';
import { CheckCircle2, XCircle } from 'lucide-react';
import { navigate, type VerificationStatus } from '../auth/route';
import { AuthLayout } from './AuthLayout';

/**
 * Where `GET /api/auth/verify-email` redirects after redeeming a token.
 *
 * This page does no work: the backend has already redeemed (or refused) the
 * token by the time the browser gets here, and carries the outcome in
 * `?status=`. It exists because the person who clicked the link is looking at a
 * browser and needs to be told what happened, rather than shown a JSON body.
 *
 * The failure case matters more than it used to. An unverified account is
 * refused the application entirely, so someone who lands here on an expired or
 * already-used link is stuck outside it — "continue" takes them to the
 * verification wall, which is where a fresh link can be requested. The wording
 * says so rather than implying they can carry on regardless.
 */
export function VerifyEmailPage({ status }: { status: VerificationStatus }) {
  const { t } = useAppTranslation();
  const verified = status === 'success';

  return (
    <AuthLayout title={verified ?t('verifyEmailPage.emailVerified') :t('verifyEmailPage.thatLinkDidnTWork')}>
      <div style={{ textAlign: 'center' }}>
        {verified ? (
          <CheckCircle2 size={40} color="var(--rose-gold-primary)" style={{ marginBottom: '16px' }} />
        ) : (
          <XCircle size={40} color="#e87c8a" style={{ marginBottom: '16px' }} />
        )}

        <p style={{ color: 'var(--text-muted)', fontSize: '14px', lineHeight: 1.6 }}>
          {verified
            ?t('verifyEmailPage.thanksYourEmailAddressIsConfirmedYourAccountIsNowFullyUnlocked')
            :t('verifyEmailPage.thisVerificationLinkIsInvalidHasExpiredOrHasAlreadyBeenUsedSignI')}
        </p>

        <button
          type="button"
          className="btn-rose"
          onClick={() => navigate('/')}
          style={{ marginTop: '24px' }}
        >
          {t('verifyEmailPage.continueToAuraBeautyLog')}
        </button>
      </div>
    </AuthLayout>
  );
}
