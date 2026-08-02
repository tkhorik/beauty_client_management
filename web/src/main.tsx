import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { AuthProvider } from './auth/AuthContext.tsx'
import { OrgProvider } from './auth/OrgContext.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider>
      {/* Inside AuthProvider: the organization list is per-user and cannot be
          fetched until there is a session to fetch it with. */}
      <OrgProvider>
        <App />
      </OrgProvider>
    </AuthProvider>
  </StrictMode>,
)
