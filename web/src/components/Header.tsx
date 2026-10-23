import { useAppTranslation } from '../i18n/LocaleProvider';
import { Search, Plus, Sparkles, Settings, Users, ShieldCheck, ArrowUpRight } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { useOrg } from '../auth/OrgContext';
import { ThemeSelector } from './ThemeSelector';
import { LanguageSelector } from './LanguageSelector';
import type { ClientTag } from '../types';
import './Workspace.css';

interface HeaderProps {
  searchQuery: string;
  onSearchChange: (q: string) => void;
  selectedTag: string;
  onTagSelect: (tag: string) => void;
  tags: ClientTag[];
  onOpenNewClient: () => void;
  onOpenNewVisit: () => void;
  onOpenSettings: () => void;
  onOpenMembers: () => void;
  onOpenAdmin: () => void;
  totalClients: number;
}

export function Header({ searchQuery, onSearchChange, selectedTag, onTagSelect, tags, onOpenNewClient, onOpenNewVisit, onOpenSettings, onOpenMembers, onOpenAdmin, totalClients }: HeaderProps) {
  const { t } = useAppTranslation();
  const { user } = useAuth();
  const { current, activeOrganizations, offline, select } = useOrg();
  const pendingCount = current?.pendingRequestCount ?? 0;
  const tagsList: Array<{ value: string; label: string; count?: number }> = [
    { value: '', label: t('header.all') },
    ...tags.map(tag => ({ value: tag.name, label: tag.name, count: tag.count })),
  ];
  if (selectedTag && !tags.some(tag => tag.name.toLowerCase() === selectedTag.toLowerCase())) tagsList.push({ value: selectedTag, label: selectedTag });

  return (
    <header className="workspace-header">
      <a className="workspace-skip" href="#client-directory-content">{t('landingPage.skip')}</a>
      <div className="workspace-topbar">
        <div className="workspace-brand" aria-label={t('common.appName')}>
          <span className="workspace-brand__mark"><Sparkles size={22} aria-hidden="true" /></span>
          <span className="workspace-brand__word">aura<small>beauty log</small></span>
        </div>
        <div className="workspace-tools">
          {current && <select className="input-field workspace-org" value={current.id} onChange={e => select(e.target.value)}
            title={offline ? t('header.offlineDemoDataTheBackendIsUnreachable') : t('header.activeOrganization')}
            aria-label={t('header.activeOrganization')} disabled={activeOrganizations.length <= 1}>
            {activeOrganizations.map(org => <option key={org.id} value={org.id}>{offline ? t('header.demoOrganization') : org.name}</option>)}
          </select>}
          <div className="display-preferences"><ThemeSelector compact /><LanguageSelector compact /></div>
          {(current?.role === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN') && !offline && (
            <button className="icon-button workspace-members" onClick={onOpenMembers} title={t('header.manageMembers')}
              aria-label={pendingCount > 0 ? `${t('header.manageMembers')} — ${t('header.pendingRequests', { count: pendingCount })}` : t('header.manageMembers')}>
              <Users size={19} aria-hidden="true" />
              {pendingCount > 0 && <span className="workspace-count" aria-hidden="true">{pendingCount > 99 ? '99+' : pendingCount}</span>}
            </button>
          )}
          {user?.globalRole === 'SUPER_ADMIN' && !offline && <button className="icon-button" onClick={onOpenAdmin} title={t('header.adminPanel')} aria-label={t('header.adminPanel')}><ShieldCheck size={19} aria-hidden="true" /></button>}
          <button className="icon-button" onClick={onOpenSettings} title={user ? t('header.signedInAs', { name: user.fullName }) : t('header.accountSettings')} aria-label={t('header.accountSettings')}><Settings size={19} aria-hidden="true" /></button>
        </div>
      </div>
      <div className="workspace-heading">
        <div>
          <p className="workspace-eyebrow">{t('header.journal')}</p>
          <h1>{t('header.clientsTitle')}<span aria-hidden="true">{totalClients}</span><span className="sr-only">, {t('counts.activeProfiles', { count: totalClients })}</span></h1>
          <p className="workspace-description">{t('header.clientsDescription')}</p>
        </div>
        <div className="workspace-actions">
          <button className="btn-secondary" onClick={onOpenNewVisit}><Plus size={18} aria-hidden="true" />{t('header.logNewVisit')}</button>
          <button className="btn-rose" onClick={onOpenNewClient}>{t('header.newClientProfile')}<ArrowUpRight size={19} aria-hidden="true" /></button>
        </div>
      </div>
      <div className="workspace-search" role="search">
        <Search size={20} aria-hidden="true" />
        <input type="search" className="input-field" aria-label={t('header.searchClientsByNamePhoneAllergiesFormulasOrProcedureNotes')}
          placeholder={t('header.searchShort')} value={searchQuery} onChange={e => onSearchChange(e.target.value)} />
      </div>
      <div className="workspace-tags" aria-label={t('header.filtersLabel')}>
        {tagsList.map(tag => <button key={tag.value} className="workspace-tag" aria-pressed={selectedTag.toLowerCase() === tag.value.toLowerCase()} onClick={() => onTagSelect(tag.value)}>
          {tag.label}{tag.count !== undefined && <span>{tag.count}</span>}
        </button>)}
      </div>
    </header>
  );
}
