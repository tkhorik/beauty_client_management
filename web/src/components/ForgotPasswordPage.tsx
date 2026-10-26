import { useAppTranslation, useLocale } from '../i18n/useLocale';
import { useEffect, useState, type FormEvent } from 'react';
import { MailCheck } from 'lucide-react';
import { API_BASE_URL } from '../config';
import { navigate } from '../auth/route';
import { AuthLayout, ErrorBanner, LinkButton } from './AuthLayout';
import { effectiveAcceptLanguage } from '../i18n/store';

/**
 * Requests a password-reset link.
 *
 * The one rule this screen has to follow: **the UI must not become the
 * enumeration oracle the API refuses to be.** `/api/auth/forgot-password`
 * answers 200 with an identical body for a registered address, an unregistered
 * one and a malformed one, precisely so that no one can use it to test which
 * addresses have accounts. If this form validated the address in the browser
 * and said "that's not a real address", or rendered anything different once the
 * response came back, it would hand back exactly the signal the backend spent
 * that effort withholding.
 *
 * So: submit whatever was typed, and show the same confirmation every time.
 *
 * Calls `fetch` directly rather than going through `services/api.ts`. That
 * module's `authFetch` attaches a bearer token and an organization header and
 * retries through a refresh on 401 — none of which applies to an endpoint whose
 * entire premise is that the caller has no session. `LoginPage` makes its
 * `/login` and `/register` calls the same way, for the same reason.
 *
 * There is also deliberately no `localStorage` fallback here. `api.ts` falls
 * back to mock data when the backend is unreachable so the demo keeps working
 * offline; a password reset cannot be faked offline, and pretending it
 * succeeded would be a lie about an account's security.
 */
export function ForgotPasswordPage() {
  const { t } = useAppTranslation();
  const { locale } = useLocale();
  const [email, setEmail] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => setError(''), [locale]);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setLoading(true);

    try {
      const res = await fetch(`${API_BASE_URL}/auth/forgot-password`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept-Language': effectiveAcceptLanguage() },
        body: JSON.stringify({ email }),
      });

      // The only response worth distinguishing. It says nothing about the
      // address — the limiter is keyed on the caller's IP, not the account —
      // and a user who is silently rate-limited would otherwise sit waiting
      // for mail that was never sent.
      if (res.status === 429) {
        setError(t('forgotPasswordPage.tooManyRequestsPleaseWaitAMinuteAndTryAgain'));
        return;
      }

      // Every other outcome, including a 500, shows the confirmation. An error
      // that appeared only for addresses the server recognises would be the
      // oracle again, by a longer route.
      setSubmitted(true);
    } catch {
      setError(t('forgotPasswordPage.serverCouldNotBeReachedPleaseCheckYourConnectionAndTryAgain'));
    } finally {
      setLoading(false);
    }
  }

  if (submitted) {
    return (
      <AuthLayout title={t('forgotPasswordPage.checkYourEmail')}>
        <div className="auth-content-center">
          <MailCheck size={40} className="auth-status-icon" />
          <p>
            {t('forgotPasswordPage.sentDescription', { email })}
          </p>
          <p className="auth-hint" style={{ marginTop: '16px' }}>
            {t('forgotPasswordPage.nothingArrivedCheckYourSpamFolderBeforeRequestingAnotherLinkEach')}
          </p>
          <div style={{ marginTop: '24px' }}>
            <LinkButton onClick={() => navigate('/')}>{t('forgotPasswordPage.backToSignIn')}</LinkButton>
          </div>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      title={t('forgotPasswordPage.resetYourPassword')}
      subtitle={t('forgotPasswordPage.enterTheEmailAddressOnYourAccountAndWeLlSendYouALinkToChooseANew')}
    >
      <form onSubmit={handleSubmit} className="auth-form">
        <div className="auth-field">
          <label htmlFor="email">
            {t('forgotPasswordPage.email')}
          </label>
          <input
            id="email"
            name="email"
            type="email"
            autoComplete="email"
            className="input-field"
            value={email}
            onChange={e => setEmail(e.target.value)}
            placeholder="you@example.com"
            required
            autoFocus
            style={{ width: '100%' }}
          />
        </div>

        {error && <ErrorBanner>{error}</ErrorBanner>}

        <button
          type="submit"
          className="btn-rose"
          disabled={loading}
          style={{ opacity: loading ? 0.7 : 1 }}
        >
          {loading ?t('forgotPasswordPage.pleaseWait') :t('forgotPasswordPage.sendResetLink')}
        </button>
      </form>

      <p className="auth-form-footer">
        {t('forgotPasswordPage.rememberedIt')}{' '}
        <LinkButton onClick={() => navigate('/')}>{t('forgotPasswordPage.backToSignIn')}</LinkButton>
      </p>
    </AuthLayout>
  );
}
