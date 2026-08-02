import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { AuthProvider } from './auth/AuthContext.tsx'
import { OrgProvider } from './auth/OrgContext.tsx'
import { usePublicRoute } from './auth/route.ts'
import { ForgotPasswordPage } from './components/ForgotPasswordPage.tsx'
import { ResetPasswordPage } from './components/ResetPasswordPage.tsx'
import { VerifyEmailPage } from './components/VerifyEmailPage.tsx'

/**
 * Chooses between the application and the three screens that must work without
 * a session.
 *
 * The public screens render *outside* both providers, which is the whole reason
 * this dispatch sits here rather than inside `App`. `AuthProvider` exchanges
 * the refresh cookie on mount and `OrgProvider` immediately fetches the
 * organization list; on a reset link neither can succeed, and the failed org
 * fetch would dispatch `beauty:unauthorized` at a page whose entire premise is
 * that the visitor is signed out.
 *
 * See `auth/route.ts` for why there is no router library.
 */
function Root() {
  const route = usePublicRoute();

  switch (route.name) {
    case 'forgot-password':
      return <ForgotPasswordPage />;

    case 'reset-password':
      return <ResetPasswordPage token={route.token} />;

    case 'verify-email':
      return <VerifyEmailPage status={route.status} />;

    default:
      return (
        <AuthProvider>
          {/* Inside AuthProvider: the organization list is per-user and cannot be
              fetched until there is a session to fetch it with. */}
          <OrgProvider>
            <App />
          </OrgProvider>
        </AuthProvider>
      );
  }
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
)
