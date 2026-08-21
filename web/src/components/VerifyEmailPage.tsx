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
  const verified = status === 'success';

  return (
    <AuthLayout title={verified ? 'Email verified' : "That link didn't work"}>
      <div style={{ textAlign: 'center' }}>
        {verified ? (
          <CheckCircle2 size={40} color="var(--rose-gold-primary)" style={{ marginBottom: '16px' }} />
        ) : (
          <XCircle size={40} color="#e87c8a" style={{ marginBottom: '16px' }} />
        )}

        <p style={{ color: 'var(--text-muted)', fontSize: '14px', lineHeight: 1.6 }}>
          {verified
            ? 'Thanks — your email address is confirmed. Your account is now fully unlocked.'
            : "This verification link is invalid, has expired, or has already been used. Sign in and we'll offer you a fresh one."}
        </p>

        <button
          type="button"
          className="btn-rose"
          onClick={() => navigate('/')}
          style={{ marginTop: '24px' }}
        >
          Continue to Aura Beauty Log
        </button>
      </div>
    </AuthLayout>
  );
}
