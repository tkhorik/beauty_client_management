import type { ReactNode } from 'react';
import { Sparkles } from 'lucide-react';
import { ThemeSelector } from './ThemeSelector';
import { LanguageSelector } from './LanguageSelector';
import { useAppTranslation } from '../i18n/useLocale';
import './AuthScreens.css';

export function AuthLayout({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  const { t } = useAppTranslation();
  return (
    <main className="auth-screen">
      <aside className="auth-screen__aside" aria-hidden="true">
        <div className="auth-screen__aside-inner">
          <div className="auth-screen__brand"><Sparkles size={22} /><span>Aura</span></div>
          <p>{t('landingPage.heroTitle')}</p>
          <div className="auth-screen__aside-note">{t('landingPage.studioCaption')}</div>
        </div>
      </aside>
      <section className="auth-screen__content" aria-label={title}>
        <div className="auth-card">
          <div className="auth-card__top"><div className="display-preferences"><ThemeSelector compact /><LanguageSelector compact /></div></div>
          <header className="auth-card__header">
            <div className="auth-card__mark" aria-hidden="true"><Sparkles size={24} /></div>
            <h1>{title}</h1>
            {subtitle && <p>{subtitle}</p>}
          </header>
          {children}
        </div>
      </section>
    </main>
  );
}

export function ErrorBanner({ children }: { children: ReactNode }) {
  return <div className="auth-alert auth-alert--error" role="alert">{children}</div>;
}

export function LinkButton({ onClick, children }: { onClick: () => void; children: ReactNode }) {
  return <button type="button" onClick={onClick} className="auth-link">{children}</button>;
}
