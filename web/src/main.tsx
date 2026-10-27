import { ThemeProvider } from './theme/ThemeProvider';
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import { LocaleProvider } from './i18n/LocaleProvider.tsx'
import { Root } from './Root.tsx'
import { initInstallPrompt } from './pwa/install'
import { initServiceWorker } from './pwa/runtime'

initInstallPrompt();
initServiceWorker();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ThemeProvider><LocaleProvider><Root /></LocaleProvider></ThemeProvider>
  </StrictMode>,
)
