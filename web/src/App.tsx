import { useEffect, useRef, useState } from 'react';
import { lazyOverlay } from './components/lazyOverlay';
import type { Client, Visit, Attachment, Organization, ClientTag } from './types';
import { api } from './services/api';
import { useAuth } from './auth/useAuth';
import { useOrg } from './auth/useOrg';
import { LoginPage, type LoginMode } from './components/LoginPage';
import { LandingPage } from './components/LandingPage';
import { OrganizationOnboarding } from './components/OrganizationOnboarding';
const MembersModal = lazyOverlay(async () => { const module = await import('./components/MembersModal'); return { default: module.MembersModal }; });
const AdminPanel = lazyOverlay(async () => { const module = await import('./components/AdminPanel'); return { default: module.AdminPanel }; });
import { Header } from './components/Header';
import { ClientCard } from './components/ClientCard';
const ClientDetailModal = lazyOverlay(async () => { const module = await import('./components/ClientDetailModal'); return { default: module.ClientDetailModal }; });
const NewClientModal = lazyOverlay(async () => { const module = await import('./components/NewClientModal'); return { default: module.NewClientModal }; });
const NewVisitModal = lazyOverlay(async () => { const module = await import('./components/NewVisitModal'); return { default: module.NewVisitModal }; });
const PhotoCompareModal = lazyOverlay(async () => { const module = await import('./components/PhotoCompareModal'); return { default: module.PhotoCompareModal }; });
const SettingsModal = lazyOverlay(async () => { const module = await import('./components/SettingsModal'); return { default: module.SettingsModal }; });
import { VerificationBanner } from './components/VerificationBanner';
import { Users, Sparkles } from 'lucide-react';
import { useAppTranslation } from './i18n/useLocale';
import { arrivedWithCreationLink, clearMembersDeepLink, membersDeepLink, pendingInviteToken, pendingJoinSlug, stripDeepLinkParams } from './auth/deepLinks';
import { InviteAcceptDialog } from './components/InviteAcceptDialog';

export function App() {
  const { token, initialising, logout } = useAuth();
  const [isAdminPanelOpen, setIsAdminPanelOpen] = useState(false);
  const [creationLinkOpen, setCreationLinkOpen] = useState(
    () => new URLSearchParams(window.location.search).has('orgToken')
  );
  // Held across sign-in/sign-up: the confirmation only appears once there is
  // a session to join with.
  const [inviteToken, setInviteToken] = useState(pendingInviteToken);
  // Signed-out visitors see the landing page first. Anyone who followed an
  // invite, join, members or creation link already knows the product and is
  // mid-flow, so they go straight to the form, as before.
  const [authScreen, setAuthScreen] = useState<'landing' | LoginMode>(() =>
    pendingInviteToken() || pendingJoinSlug() || membersDeepLink() || arrivedWithCreationLink ? 'login' : 'landing'
  );

  // Listen for 401 responses emitted by authFetch and force logout.
  // authFetch only emits this after a refresh attempt has already failed, so
  // by the time it fires the session really is over.
  useEffect(() => {
    const handler = () => logout();
    window.addEventListener('beauty:unauthorized', handler);
    return () => window.removeEventListener('beauty:unauthorized', handler);
  }, [logout]);

  // `?join=` / `?members=` / `?invite=` were captured in memory when the app loaded.
  useEffect(() => { stripDeepLinkParams(); }, []);

  // On a reload the access token is always briefly absent while the refresh
  // cookie is exchanged. Rendering the login page during that window would
  // flash a login form at a user who is in fact signed in.
  if (initialising) return <ScreenLoading />;

  // Show the landing page, then the login form, when unauthenticated
  if (!token) {
    if (authScreen === 'landing') {
      return <LandingPage onSignIn={() => setAuthScreen('login')} onGetStarted={() => setAuthScreen('register')} />;
    }
    return <LoginPage key={authScreen} initialMode={authScreen} onBack={() => setAuthScreen('landing')} />;
  }

  return (
    <>
      {/*
        Rendered as a sibling of the organization-scoped tree, not nested
        inside it: a SUPER_ADMIN may belong to zero organizations (a freshly
        bootstrapped one certainly does), and the admin panel is the only way
        such an account can ever issue its first organization-creation link.
        Nesting this inside AuthenticatedApp would make it unreachable exactly
        when it is needed most. The entry point is offered from both
        OrganizationGate's onboarding screen and, once an organization exists,
        from the header — see `onOpenAdmin` below.
      */}
      <OrganizationGate
        onOpenAdmin={() => setIsAdminPanelOpen(true)}
        creationLinkOpen={creationLinkOpen}
        onCloseCreationLink={() => setCreationLinkOpen(false)}
      />
      {isAdminPanelOpen && <AdminPanel onClose={() => setIsAdminPanelOpen(false)} />}
      {inviteToken && <InviteAcceptDialog token={inviteToken} onDone={() => setInviteToken(null)} />}
    </>
  );
}

/**
 * Stands between a valid session and the app proper.
 *
 * Clients and visits belong to an organization, so with none selected there is
 * nothing to render and the backend answers every data request with
 * `MISSING_ORGANIZATION`. Rather than let the user watch an empty grid fail to
 * load, send them somewhere they can actually do something about it.
 */
function OrganizationGate({
  onOpenAdmin,
  creationLinkOpen,
  onCloseCreationLink,
}: {
  onOpenAdmin: () => void;
  creationLinkOpen: boolean;
  onCloseCreationLink: () => void;
}) {
  const { current, loading, organizations, select, refresh, connectionError } = useOrg();
  const { user } = useAuth();
  const { t } = useAppTranslation();

  // A "new access request" email links to `?members=<orgId>`. Switch to that
  // organization first — only if the caller can actually manage it — and let
  // AuthenticatedApp open the members screen once it is the current one.
  const membersTarget = membersDeepLink();
  useEffect(() => {
    if (loading || !membersTarget || current?.id === membersTarget) return;
    const target = organizations.find(o => o.id === membersTarget && o.status === 'ACTIVE');
    const canManage = target && (target.role === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN');
    if (canManage) select(membersTarget);
    else clearMembersDeepLink();
  }, [loading, membersTarget, current?.id, organizations, select, user?.globalRole]);

  // Same reasoning as `initialising` above: a brief null while the list loads
  // is not the same as "has no organization", and flashing the onboarding
  // screen at someone who has three salons would be alarming.
  // Once an organization has been verified, refreshing it must not unmount the
  // workspace. Unmounting here would discard in-progress form state merely
  // because the connection was checked again.
  if (loading && !current) return <ScreenLoading />;

  // A production network failure is neither proof that the user has no salon
  // nor a reason to invent demo data. Keep the connection problem explicit and
  // retryable before sending a first-load user to organization onboarding.
  if (!current && connectionError) {
    return (
      <div className="auth-page" style={{ padding: '24px 32px' }}>
        <div className="auth-card auth-alert auth-alert--error" role="alert">
          <p>{t('common.network')}</p>
          <button className="btn-secondary" type="button" onClick={() => void refresh()}>{t('clients.retry')}</button>
        </div>
      </div>
    );
  }

  // The banner wraps the onboarding screen too, not just the app proper.
  // Creating an organization is one of the actions the restriction blocks, so
  // a user inside the grace window can land here, hit a 403 on the only button
  // available, and have no idea why unless the notice is on this screen as
  // well. An already-restricted user never reaches this component at all —
  // `VerificationGate` in `main.tsx` replaces the whole tree with the wall.
  if (!current || creationLinkOpen) {
    return (
      <div style={{ padding: '24px 32px', maxWidth: '1400px', margin: '0 auto' }}>
        <VerificationBanner />
        <OrganizationOnboarding
          onOpenAdmin={onOpenAdmin}
          onCreated={onCloseCreationLink}
          onCancel={current ? onCloseCreationLink : undefined}
        />
      </div>
    );
  }

  // Keyed on the organization id so switching salons remounts the whole app.
  // Without the key, React keeps the previous organization's clients, selected
  // client and open modals mounted while the new data loads — showing one
  // tenant's records under another's name, which is the exact confusion this
  // feature exists to prevent.
  return (
    <>
      {connectionError && (
        <div className="notice notice--error" role="alert">
          <span>{t('common.network')}</span>
          <button className="btn-secondary" type="button" onClick={() => void refresh()}>{t('clients.retry')}</button>
        </div>
      )}
      <AuthenticatedApp key={current.id} organization={current} onOpenAdmin={onOpenAdmin} />
    </>
  );
}

/**
 * `organization` is passed down rather than re-read from `useOrg()` inside:
 * this component only ever renders when one is selected, and threading it
 * through the props keeps that guarantee in the type rather than forcing a
 * non-null assertion at every use.
 */
function AuthenticatedApp({
  organization,
  onOpenAdmin
}: {
  organization: Organization;
  onOpenAdmin: () => void;
}) {
  const { t } = useAppTranslation();
  const { user } = useAuth();
  const [clients, setClients] = useState<Client[]>([]);
  // The search and tag the current list was loaded for, so a failed load for
  // a new filter never presents the previous results as matches.
  const [clientsFilter, setClientsFilter] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [clientsError, setClientsError] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedTag, setSelectedTag] = useState('');
  const [tags, setTags] = useState<ClientTag[]>([]);

  // Modals state
  const [selectedClient, setSelectedClient] = useState<Client | null>(null);
  const [selectedClientVisits, setSelectedClientVisits] = useState<Visit[]>([]);
  const [visitsLoading, setVisitsLoading] = useState(false);
  const [visitsError, setVisitsError] = useState(false);
  const [refreshGeneration, setRefreshGeneration] = useState(0);
  const clientRequest = useRef(0);
  const detailRequest = useRef(0);
  const [isNewClientOpen, setIsNewClientOpen] = useState(false);
  const [isNewVisitOpen, setIsNewVisitOpen] = useState(false);
  const [newVisitTargetClient, setNewVisitTargetClient] = useState<Client | undefined>(undefined);
  const [compareAttachments, setCompareAttachments] = useState<Attachment[] | null>(null);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  // Opened straight away when the app was reached from a "new access request"
  // email for this organization — see OrganizationGate. Read-only here;
  // cleared by the effect below, so StrictMode's doubled initializer is safe.
  const [isMembersOpen, setIsMembersOpen] = useState(
    () => membersDeepLink() === organization.id
      && (organization.role === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN')
  );
  useEffect(() => {
    if (membersDeepLink() === organization.id) clearMembersDeepLink();
  }, [organization.id]);

  // Refresh via state so delayed mutation callbacks always use today's filters
  // and selection, not the values captured when a form was submitted.
  const loadData = () => setRefreshGeneration(generation => generation + 1);

  // Each new filter or refresh starts a request. Mark it in flight during
  // render, so no frame shows the previous outcome as the new one's.
  const clientsKey = JSON.stringify([searchQuery, selectedTag, refreshGeneration]);
  const [clientsRequestKey, setClientsRequestKey] = useState(clientsKey);
  if (clientsRequestKey !== clientsKey) {
    setClientsRequestKey(clientsKey);
    setLoading(true);
    setClientsError(false);
  }

  useEffect(() => {
    const request = ++clientRequest.current;
    let active = true;
    const filterKey = JSON.stringify([searchQuery, selectedTag]);
    void api.getClients(searchQuery, selectedTag).then(data => {
      if (active && request === clientRequest.current) {
        setClients(data);
        setClientsFilter(filterKey);
      }
    }).catch(err => {
      if (active && request === clientRequest.current) {
        setClientsError(true);
        console.error('Error loading client data:', err);
      }
    }).finally(() => {
      if (active && request === clientRequest.current) setLoading(false);
    });
    return () => { active = false; };
  }, [searchQuery, selectedTag, refreshGeneration]);

  // Not narrowed by the search or the selected tag: the chips offer every
  // tag in the organization, refreshed whenever a client may have changed.
  useEffect(() => {
    let active = true;
    void api.getClientTags().then(data => {
      if (active) setTags(data);
    }).catch(err => console.error('Error loading client tags:', err));
    return () => { active = false; };
  }, [refreshGeneration]);

  const selectedClientId = selectedClient?.id;
  // Same for the open client's details: reset on a new client or a refresh.
  const detailKey = selectedClientId ? JSON.stringify([selectedClientId, refreshGeneration]) : null;
  const [detailRequestKey, setDetailRequestKey] = useState(detailKey);
  if (detailRequestKey !== detailKey) {
    setDetailRequestKey(detailKey);
    if (detailKey) {
      setSelectedClientVisits([]);
      setVisitsLoading(true);
      setVisitsError(false);
    }
  }

  useEffect(() => {
    if (!selectedClientId) return;
    const request = ++detailRequest.current;
    let active = true;
    void Promise.all([api.getClient(selectedClientId), api.getVisits(selectedClientId)]).then(([client, visits]) => {
      if (!active || request !== detailRequest.current) return;
      if (client) setSelectedClient(client);
      setSelectedClientVisits(visits);
    }).catch(() => {
      if (active && request === detailRequest.current) setVisitsError(true);
    }).finally(() => {
      if (active && request === detailRequest.current) setVisitsLoading(false);
    });
    return () => { active = false; };
  }, [selectedClientId, refreshGeneration]);

  const handleSelectClient = (client: Client) => {
    if (selectedClient?.id === client.id) return;
    ++detailRequest.current;
    setSelectedClientVisits([]);
    setVisitsLoading(true);
    setVisitsError(false);
    setSelectedClient(client);
  };

  const handleCloseClient = () => {
    ++detailRequest.current;
    setSelectedClient(null);
    setSelectedClientVisits([]);
  };

  const handleOpenNewVisit = (client?: Client) => {
    setNewVisitTargetClient(client);
    setIsNewVisitOpen(true);
  };

  const listMatchesFilter = clientsFilter === JSON.stringify([searchQuery, selectedTag]);
  const visibleClients = clientsError && !listMatchesFilter ? [] : clients;

  return (
    <div className="client-directory">
      
      {/* Navigation Header */}
      <Header
        searchQuery={searchQuery}
        onSearchChange={setSearchQuery}
        selectedTag={selectedTag}
        onTagSelect={setSelectedTag}
        tags={tags}
        onOpenNewClient={() => setIsNewClientOpen(true)}
        onOpenNewVisit={() => handleOpenNewVisit(undefined)}
        onOpenSettings={() => setIsSettingsOpen(true)}
        onOpenMembers={() => setIsMembersOpen(true)}
        onOpenAdmin={onOpenAdmin}
        totalClients={visibleClients.length}
      />

      {/* Renders nothing at all for a verified account. */}
      <VerificationBanner />

      {/* Main Grid Content */}
      <main id="client-directory-content" tabIndex={-1} aria-busy={loading}>
        {clientsError && <div className="notice notice--error directory-error" role="alert"><span>{t('clients.failed')}</span><button className="btn-secondary" onClick={loadData}>{t('clients.retry')}</button></div>}
        {loading ? (
          <div className="directory-loading" role="status">
            <p>{t('clients.loading')}</p>
          </div>
        ) : clientsError && visibleClients.length === 0 ? null : visibleClients.length === 0 ? (
          <div className="directory-empty">
            <Users size={48} color="var(--rose-gold-primary)" style={{ opacity: 0.7, marginBottom: '16px' }} />
            <h2 className="text-gradient" style={{ fontSize: '22px', marginBottom: '8px' }}>{t('clients.none')}</h2>
            <p style={{ color: 'var(--text-muted)', marginBottom: '20px' }}>
              {searchQuery || selectedTag ? t('clients.noMatch') : t('clients.first')}
            </p>
            <button className="btn-rose" onClick={() => setIsNewClientOpen(true)}>
              <Sparkles size={18} /> {t('clients.create')}
            </button>
          </div>
        ) : (
          <div className="client-grid">
            {visibleClients.map(client => (
              <ClientCard
                key={client.id}
                client={client}
                onSelect={handleSelectClient}
                onLogVisit={(c) => handleOpenNewVisit(c)}
              />
            ))}
          </div>
        )}
      </main>

      {/* Client Detail Modal */}
      {selectedClient && (
        <ClientDetailModal
          key={selectedClient.id}
          client={selectedClient}
          visits={selectedClientVisits}
          visitsLoading={visitsLoading}
          visitsError={visitsError}
          onClose={handleCloseClient}
          onRefresh={loadData}
          onOpenNewVisit={(c) => handleOpenNewVisit(c)}
          onOpenPhotoCompare={(atts) => setCompareAttachments(atts)}
        />
      )}

      {/* New Client Modal */}
      {isNewClientOpen && (
        <NewClientModal
          onClose={() => setIsNewClientOpen(false)}
          onSuccess={loadData}
        />
      )}

      {/* New Visit Modal */}
      {isNewVisitOpen && (
        <NewVisitModal
          client={newVisitTargetClient}
          clientsList={clients}
          onClose={() => setIsNewVisitOpen(false)}
          onSuccess={loadData}
        />
      )}

      {/* Photo Comparison Modal */}
      {compareAttachments && (
        <PhotoCompareModal
          attachments={compareAttachments}
          onClose={() => setCompareAttachments(null)}
        />
      )}

      {/* Account Settings Modal */}
      {isSettingsOpen && (
        <SettingsModal onClose={() => setIsSettingsOpen(false)} />
      )}

      {/* Organization Members Modal (administrators and super admins) */}
      {isMembersOpen && (
        <MembersModal
          orgId={organization.id}
          orgName={organization.name}
          onClose={() => setIsMembersOpen(false)}
        />
      )}
    </div>
  );
}

function ScreenLoading() {
  const { t } = useAppTranslation();
  return <div className="screen-loading" role="status"><Sparkles size={28} aria-hidden="true" /><p>{t('common.loading')}</p></div>;
}

export default App;
