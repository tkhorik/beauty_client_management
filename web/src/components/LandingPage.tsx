import type { ReactNode } from 'react';
import {
  CalendarClock, CheckCircle2, Images, Search, ShieldCheck, Smartphone, Sparkles,
  UserCog, UserRound, Users, WifiOff, Contact,
} from 'lucide-react';
import { LanguageSelector } from './LanguageSelector';
import { useAppTranslation } from '../i18n/LocaleProvider';
import type { TranslationKey } from '../i18n/locales';
import { ANDROID_DOWNLOAD_URL } from '../nativeAppBanner';

const FEATURES: { icon: ReactNode; title: TranslationKey; body: TranslationKey }[] = [
  { icon: <Contact size={22} />, title: 'landingPage.featureClientsTitle', body: 'landingPage.featureClientsBody' },
  { icon: <CalendarClock size={22} />, title: 'landingPage.featureVisitsTitle', body: 'landingPage.featureVisitsBody' },
  { icon: <Images size={22} />, title: 'landingPage.featurePhotosTitle', body: 'landingPage.featurePhotosBody' },
  { icon: <Search size={22} />, title: 'landingPage.featureSearchTitle', body: 'landingPage.featureSearchBody' },
  { icon: <Users size={22} />, title: 'landingPage.featureTeamTitle', body: 'landingPage.featureTeamBody' },
  { icon: <WifiOff size={22} />, title: 'landingPage.featureOfflineTitle', body: 'landingPage.featureOfflineBody' },
];

const STEPS: { title: TranslationKey; body: TranslationKey }[] = [
  { title: 'landingPage.step1Title', body: 'landingPage.step1Body' },
  { title: 'landingPage.step2Title', body: 'landingPage.step2Body' },
  { title: 'landingPage.step3Title', body: 'landingPage.step3Body' },
  { title: 'landingPage.step4Title', body: 'landingPage.step4Body' },
  { title: 'landingPage.step5Title', body: 'landingPage.step5Body' },
];

const SECURITY_POINTS: TranslationKey[] = [
  'landingPage.securityIsolation',
  'landingPage.securityRevoke',
  'landingPage.securityAccounts',
];

/**
 * What a signed-out visitor sees before the login form: what the product does,
 * how to get going, who can see what, and where the Android app is.
 *
 * Purely presentational — no requests, no session. `App` swaps it for
 * `LoginPage` when either call to action is used.
 */
export function LandingPage({ onSignIn, onGetStarted }: { onSignIn: () => void; onGetStarted: () => void }) {
  const { t } = useAppTranslation();

  const ctaRow = (
    <div className="landing-cta-row">
      <button type="button" className="btn-rose" onClick={onGetStarted}>{t('landingPage.getStarted')}</button>
      <button type="button" className="btn-secondary" onClick={onSignIn}>{t('landingPage.signIn')}</button>
    </div>
  );

  return (
    <div className="landing">
      <header className="landing-topbar">
        <div className="landing-container landing-topbar__inner">
          <div className="landing-brand">
            <Sparkles size={22} color="var(--rose-gold-primary)" aria-hidden="true" />
            <span>{t('common.appName')}</span>
          </div>
          <div className="landing-topbar__actions">
            <LanguageSelector compact />
            <button type="button" className="btn-secondary landing-topbar__signin" onClick={onSignIn}>
              {t('landingPage.signIn')}
            </button>
          </div>
        </div>
      </header>

      <main>
        <section className="landing-container landing-hero" aria-labelledby="landing-hero-title">
          <div>
            <span className="landing-eyebrow">{t('landingPage.eyebrow')}</span>
            <h1 id="landing-hero-title" className="text-gradient">{t('landingPage.heroTitle')}</h1>
            <p className="landing-lead">{t('landingPage.heroSubtitle')}</p>
            {ctaRow}
          </div>

          {/* Illustration only: a sample card in the app's own visual language. */}
          <figure className="glass-panel-glow landing-preview" style={{ borderRadius: 'var(--radius-lg)' }} aria-label={t('landingPage.previewLabel')}>
            <div className="landing-preview__head">
              <div className="landing-preview__avatar" aria-hidden="true">{t('landingPage.previewName').charAt(0)}</div>
              <div>
                <div className="landing-preview__name">{t('landingPage.previewName')}</div>
                <div className="landing-preview__tags">
                  <span className="tag-badge tag-vip">{t('landingPage.previewTagVip')}</span>
                  <span className="tag-badge tag-sensitive">{t('landingPage.previewTagSkin')}</span>
                </div>
              </div>
            </div>
            <div className="landing-preview__field">{t('landingPage.previewField')}</div>
            <ul className="landing-timeline">
              <li>{t('landingPage.previewVisit1')}</li>
              <li>{t('landingPage.previewVisit2')}</li>
              <li>{t('landingPage.previewVisit3')}</li>
            </ul>
            <div className="landing-preview__photos">
              <Images size={16} aria-hidden="true" /> {t('landingPage.previewPhotos')}
            </div>
          </figure>
        </section>

        <section className="landing-section landing-section--alt" aria-labelledby="landing-features-title">
          <div className="landing-container">
            <div className="landing-section__head">
              <h2 id="landing-features-title">{t('landingPage.featuresTitle')}</h2>
              <p>{t('landingPage.featuresSubtitle')}</p>
            </div>
            <div className="landing-grid">
              {FEATURES.map(feature => (
                <article key={feature.title} className="glass-panel landing-card">
                  <div className="landing-icon" aria-hidden="true">{feature.icon}</div>
                  <h3>{t(feature.title)}</h3>
                  <p>{t(feature.body)}</p>
                </article>
              ))}
            </div>
          </div>
        </section>

        <section className="landing-section" aria-labelledby="landing-steps-title">
          <div className="landing-container">
            <div className="landing-section__head">
              <h2 id="landing-steps-title">{t('landingPage.stepsTitle')}</h2>
            </div>
            <ol className="landing-steps">
              {STEPS.map(step => (
                <li key={step.title} className="glass-panel landing-step">
                  <h3>{t(step.title)}</h3>
                  <p>{t(step.body)}</p>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section className="landing-section landing-section--alt" aria-labelledby="landing-security-title">
          <div className="landing-container">
            <div className="landing-section__head">
              <h2 id="landing-security-title">{t('landingPage.securityTitle')}</h2>
              <p>{t('landingPage.securitySubtitle')}</p>
            </div>
            <div className="landing-roles">
              <article className="glass-panel landing-card">
                <div className="landing-icon" aria-hidden="true"><UserCog size={22} /></div>
                <h3>{t('landingPage.roleAdminTitle')}</h3>
                <p>{t('landingPage.roleAdminBody')}</p>
              </article>
              <article className="glass-panel landing-card">
                <div className="landing-icon" aria-hidden="true"><UserRound size={22} /></div>
                <h3>{t('landingPage.roleStaffTitle')}</h3>
                <p>{t('landingPage.roleStaffBody')}</p>
              </article>
            </div>
            <ul className="landing-checks">
              {SECURITY_POINTS.map(point => (
                <li key={point}><CheckCircle2 size={18} aria-hidden="true" />{t(point)}</li>
              ))}
            </ul>
          </div>
        </section>

        <section className="landing-section" aria-labelledby="landing-android-title">
          <div className="landing-container">
            <div className="glass-panel landing-android" style={{ borderRadius: 'var(--radius-lg)' }}>
              <div className="landing-android__icon" aria-hidden="true"><Smartphone size={40} /></div>
              <div>
                <h2 id="landing-android-title">{t('landingPage.androidTitle')}</h2>
                <p>{t('landingPage.androidBody')}</p>
                <div className="landing-cta-row">
                  <a className="btn-rose landing-link" href={ANDROID_DOWNLOAD_URL} target="_blank" rel="noopener noreferrer">
                    <Smartphone size={16} aria-hidden="true" /> {t('landingPage.androidDownload')}
                  </a>
                </div>
                <p className="landing-note">{t('landingPage.androidIosNote')}</p>
              </div>
            </div>
          </div>
        </section>

        <section className="landing-section--alt landing-final" aria-labelledby="landing-final-title">
          <div className="landing-container">
            <ShieldCheck size={32} color="var(--rose-gold-primary)" aria-hidden="true" style={{ marginBottom: '14px' }} />
            <h2 id="landing-final-title" className="text-gradient">{t('landingPage.ctaTitle')}</h2>
            <p>{t('landingPage.ctaBody')}</p>
            {ctaRow}
          </div>
        </section>
      </main>

      <footer className="landing-footer">
        <div className="landing-container landing-footer__inner">
          <span>© {new Date().getFullYear()} {t('common.appName')}</span>
          <span>{t('landingPage.footerScope')}</span>
        </div>
      </footer>
    </div>
  );
}
