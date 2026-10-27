import { useState, type ReactNode } from 'react';
import {
  CalendarClock, CheckCircle2, Images, Search, ShieldCheck, Smartphone, Sparkles,
  UserCog, UserRound, Users, WifiOff, Contact, ArrowUpRight, ArrowDown, Leaf, Check,
} from 'lucide-react';
import { ThemeSelector } from './ThemeSelector';
import { LanguageSelector } from './LanguageSelector';
import './LandingPage.css';
import { useAppTranslation, useLocale } from '../i18n/useLocale';
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
  const [year] = useState(() => new Date().getFullYear());

  const ctaRow = (
    <div className="landing-cta-row">
      <button type="button" className="btn-rose" onClick={onGetStarted}>{t('landingPage.getStarted')}</button>
      <button type="button" className="btn-secondary" onClick={onSignIn}>{t('landingPage.signIn')}</button>
    </div>
  );

  return (
    <div className="landing landing-editorial">
      <a className="landing-skip" href="#landing-main">{t('landingPage.skip')}</a>
      <header className="landing-topbar">
        <div className="landing-container landing-topbar__inner">
          <div className="landing-brand">
            <span className="landing-brand__mark" aria-hidden="true"><Sparkles size={22} /></span>
            <span className="landing-brand__word">aura<span>beauty log</span></span>
          </div>
          <nav className="landing-nav" aria-label={t('landingPage.navigation')}>
            <a href="#landing-features-title">{t('landingPage.navFeatures')}</a>
            <a href="#landing-steps-title">{t('landingPage.navSteps')}</a>
            <a href="#landing-android-title">Android <ArrowUpRight size={14} aria-hidden="true" /></a>
          </nav>
          <div className="landing-topbar__actions">
            <div className="display-preferences"><ThemeSelector compact /><LanguageSelector compact /></div>
            <button type="button" className="btn-secondary landing-topbar__signin" onClick={onSignIn}>
              {t('landingPage.signIn')}
            </button>
          </div>
        </div>
      </header>

      <main id="landing-main" tabIndex={-1}>
        <section className="landing-container landing-hero" aria-labelledby="landing-hero-title">
          <div className="landing-hero__copy">
            <span className="landing-eyebrow"><span aria-hidden="true" />{t('landingPage.eyebrow')}</span>
            <h1 id="landing-hero-title">{t('landingPage.heroTitle')}</h1>
            <p className="landing-lead">{t('landingPage.heroSubtitle')}</p>
            <div className="landing-cta-row">
              <button type="button" className="btn-rose" onClick={onGetStarted}>{t('landingPage.getStarted')}<ArrowUpRight size={20} aria-hidden="true" /></button>
              <button type="button" className="btn-secondary" onClick={onSignIn}>{t('landingPage.signIn')}</button>
            </div>
            <p className="landing-hero__note"><span aria-hidden="true" /><span>{t('landingPage.heroNote')}</span></p>
          </div>

          <div className="landing-studio">
            <div className="landing-studio__top"><span>{t('landingPage.studioBrand')}</span><Leaf size={22} aria-hidden="true" /></div>
            <div className="landing-studio__orbit" aria-hidden="true" />
            <figure className="landing-preview" aria-label={t('landingPage.previewLabel')}>
              <figcaption className="landing-preview__caption"><span>{t('landingPage.previewLabel')}</span><span aria-hidden="true">01 / 03</span></figcaption>
              <div className="landing-preview__head">
                <div className="landing-preview__avatar" aria-hidden="true">{t('landingPage.previewInitials')}</div>
                <div>
                  <div className="landing-preview__name">{t('landingPage.previewName')}</div>
                  <div className="landing-preview__tags"><span className="tag-badge">{t('landingPage.previewTagVip')}</span></div>
                </div>
              </div>
              <div className="landing-preview__field"><span>{t('landingPage.previewNoteLabel')}</span>{t('landingPage.previewField')}</div>
              <div className="landing-preview__history">{t('landingPage.featureVisitsTitle')}<span>03</span></div>
              <ul className="landing-timeline">
                <li><PreviewDate daysAgo={1} /><span>{t('landingPage.previewVisit1')}<small>{t('landingPage.previewCompleted')}</small></span><Check size={16} aria-hidden="true" /></li>
                <li><PreviewDate daysAgo={44} /><span>{t('landingPage.previewVisit2')}</span></li>
                <li><PreviewDate daysAgo={69} /><span>{t('landingPage.previewVisit3')}</span></li>
              </ul>
              <div className="landing-preview__photos"><Images size={17} aria-hidden="true" />{t('landingPage.previewPhotos')}</div>
            </figure>
            <div className="landing-studio__seal"><Sparkles size={18} aria-hidden="true" /><span>{t('landingPage.studioSeal')}</span></div>
            <p className="landing-studio__bottom">{t('landingPage.studioCaption')}<span aria-hidden="true">↗</span></p>
          </div>
        </section>

        <div className="landing-promise landing-container">
          <p>{t('landingPage.promise')}</p>
          <a href="#landing-features-title">{t('landingPage.explore')}<ArrowDown size={18} aria-hidden="true" /></a>
        </div>
        <section className="landing-section landing-section--alt" aria-labelledby="landing-features-title">
          <div className="landing-container">
            <div className="landing-section__head">
              <span className="landing-kicker">01 / {t('landingPage.navFeatures')}</span>
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
              <span className="landing-kicker">02 / {t('landingPage.navSteps')}</span>
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
              <span className="landing-kicker">03 / {t('landingPage.navSecurity')}</span>
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
            <h2 id="landing-final-title" >{t('landingPage.ctaTitle')}</h2>
            <p>{t('landingPage.ctaBody')}</p>
            {ctaRow}
          </div>
        </section>
      </main>

      <footer className="landing-footer">
        <div className="landing-container landing-footer__inner">
          <span>© {year} {t('common.appName')}</span>
          <span>{t('landingPage.footerScope')}</span>
        </div>
      </footer>
    </div>
  );
}

/** Sample visit dates stay recent and follow the visitor's locale. */
function PreviewDate({ daysAgo }: { daysAgo: number }) {
  const { formatDate } = useLocale();
  const [date] = useState(() => {
    const day = new Date();
    day.setDate(day.getDate() - daysAgo);
    return day;
  });
  const iso = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  return <time dateTime={iso}>{formatDate(date, { day: '2-digit', month: '2-digit' })}</time>;
}
