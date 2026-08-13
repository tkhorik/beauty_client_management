import { useEffect, useState } from 'react';
import type { Client, Visit, Attachment } from './types';
import { api } from './services/api';
import { useAuth } from './auth/AuthContext';
import { useOrg } from './auth/OrgContext';
import { LoginPage } from './components/LoginPage';
import { OrganizationOnboarding } from './components/OrganizationOnboarding';
import { MembersModal } from './components/MembersModal';
import { AdminPanel } from './components/AdminPanel';
import { Header } from './components/Header';
import { ClientCard } from './components/ClientCard';
import { ClientDetailModal } from './components/ClientDetailModal';
import { NewClientModal } from './components/NewClientModal';
import { NewVisitModal } from './components/NewVisitModal';
import { PhotoCompareModal } from './components/PhotoCompareModal';
import { SettingsModal } from './components/SettingsModal';
import { VerificationBanner } from './components/VerificationBanner';
import { Users, Sparkles } from 'lucide-react';

export function App() {
  const { token, initialising, logout } = useAuth();
  const [isAdminPanelOpen, setIsAdminPanelOpen] = useState(false);

  // Listen for 401 responses emitted by authFetch and force logout.
  // authFetch only emits this after a refresh attempt has already failed, so
  // by the time it fires the session really is over.
  useEffect(() => {
    const handler = () => logout();
    window.addEventListener('beauty:unauthorized', handler);
    return () => window.removeEventListener('beauty:unauthorized', handler);
  }, [logout]);

  // On a reload the access token is always briefly absent while the refresh
  // cookie is exchanged. Rendering the login page during that window would
  // flash a login form at a user who is in fact signed in.
  if (initialising) return null;

  // Show login page when unauthenticated
  if (!token) return <LoginPage />;

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
      <OrganizationGate onOpenAdmin={() => setIsAdminPanelOpen(true)} />
      {isAdminPanelOpen && <AdminPanel onClose={() => setIsAdminPanelOpen(false)} />}
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
function OrganizationGate({ onOpenAdmin }: { onOpenAdmin: () => void }) {
  const { current, loading } = useOrg();

  // Same reasoning as `initialising` above: a brief null while the list loads
  // is not the same as "has no organization", and flashing the onboarding
  // screen at someone who has three salons would be alarming.
  if (loading) return null;

  // The banner wraps the onboarding screen too, not just the app proper.
  // Creating an organization is one of the actions the restriction blocks, so
  // an unverified user can land here, hit a 403 on the only button available,
  // and have no idea why unless the explanation is on this screen as well.
  if (!current) {
    return (
      <div style={{ padding: '24px 32px', maxWidth: '1400px', margin: '0 auto' }}>
        <VerificationBanner />
        <OrganizationOnboarding onOpenAdmin={onOpenAdmin} />
      </div>
    );
  }

  // Keyed on the organization id so switching salons remounts the whole app.
  // Without the key, React keeps the previous organization's clients, selected
  // client and open modals mounted while the new data loads — showing one
  // tenant's records under another's name, which is the exact confusion this
  // feature exists to prevent.
  return <AuthenticatedApp key={current.id} onOpenAdmin={onOpenAdmin} />;
}

function AuthenticatedApp({ onOpenAdmin }: { onOpenAdmin: () => void }) {
  const [clients, setClients] = useState<Client[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedTag, setSelectedTag] = useState('');

  // Modals state
  const [selectedClient, setSelectedClient] = useState<Client | null>(null);
  const [selectedClientVisits, setSelectedClientVisits] = useState<Visit[]>([]);
  const [isNewClientOpen, setIsNewClientOpen] = useState(false);
  const [isNewVisitOpen, setIsNewVisitOpen] = useState(false);
  const [newVisitTargetClient, setNewVisitTargetClient] = useState<Client | undefined>(undefined);
  const [compareAttachments, setCompareAttachments] = useState<Attachment[] | null>(null);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isMembersOpen, setIsMembersOpen] = useState(false);

  const loadData = async () => {
    setLoading(true);
    try {
      const data = await api.getClients(searchQuery, selectedTag);
      setClients(data);
      if (selectedClient) {
        const updatedClient = await api.getClient(selectedClient.id);
        if (updatedClient) setSelectedClient(updatedClient);
        const visits = await api.getVisits(selectedClient.id);
        setSelectedClientVisits(visits);
      }
    } catch (err) {
      console.error('Error loading client data:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [searchQuery, selectedTag]);

  const handleSelectClient = async (client: Client) => {
    setSelectedClient(client);
    const visits = await api.getVisits(client.id);
    setSelectedClientVisits(visits);
  };

  const handleOpenNewVisit = (client?: Client) => {
    setNewVisitTargetClient(client);
    setIsNewVisitOpen(true);
  };

  return (
    <div style={{ minHeight: '100vh', padding: '24px 32px', maxWidth: '1400px', margin: '0 auto' }}>
      
      {/* Navigation Header */}
      <Header
        searchQuery={searchQuery}
        onSearchChange={setSearchQuery}
        selectedTag={selectedTag}
        onTagSelect={setSelectedTag}
        onOpenNewClient={() => setIsNewClientOpen(true)}
        onOpenNewVisit={() => handleOpenNewVisit(undefined)}
        onOpenSettings={() => setIsSettingsOpen(true)}
        onOpenMembers={() => setIsMembersOpen(true)}
        onOpenAdmin={onOpenAdmin}
        totalClients={clients.length}
      />

      {/* Renders nothing at all for a verified account. */}
      <VerificationBanner />

      {/* Main Grid Content */}
      <main>
        {loading ? (
          <div style={{ textAlign: 'center', padding: '60px', color: 'var(--rose-gold-primary)' }}>
            <p>Loading Beauty Client Directory & Procedure Logs...</p>
          </div>
        ) : clients.length === 0 ? (
          <div className="glass-panel" style={{ padding: '60px', textAlign: 'center', borderRadius: '20px' }}>
            <Users size={48} color="var(--rose-gold-primary)" style={{ opacity: 0.7, marginBottom: '16px' }} />
            <h2 className="text-gradient" style={{ fontSize: '22px', marginBottom: '8px' }}>No Client Profiles Found</h2>
            <p style={{ color: 'var(--text-muted)', marginBottom: '20px' }}>
              {searchQuery || selectedTag ? 'No clients match your filter criteria.' : 'Get started by creating your first beauty client record.'}
            </p>
            <button className="btn-rose" onClick={() => setIsNewClientOpen(true)}>
              <Sparkles size={18} /> Create Client Profile
            </button>
          </div>
        ) : (
          <div style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(360px, 1fr))',
            gap: '24px'
          }}>
            {clients.map(client => (
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
          client={selectedClient}
          visits={selectedClientVisits}
          onClose={() => setSelectedClient(null)}
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

      {/* Organization Members Modal (administrators only) */}
      {isMembersOpen && (
        <MembersModal onClose={() => setIsMembersOpen(false)} />
      )}
    </div>
  );
}

export default App;
