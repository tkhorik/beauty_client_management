import type { Client } from '../types';
import { Phone, Calendar, ArrowUpRight, Plus } from 'lucide-react';
import { useAppTranslation, useLocale } from '../i18n/useLocale';
import { formatPhone, phoneIssue } from './phone';
import './ClientCard.css';

interface ClientCardProps {
  client: Client;
  onSelect: (client: Client) => void;
  onLogVisit: (client: Client) => void;
}

export function ClientCard({ client, onSelect, onLogVisit }: ClientCardProps) {
  const { t } = useAppTranslation();
  const { formatDate } = useLocale();
  const visibleTags = client.tags.slice(0, 2);
  const remainingTags = client.tags.slice(2);
  const initials = client.name.trim().split(/\s+/).slice(0, 2).map(part => part[0]).join('');

  return (
    <article className="client-card">
      <button type="button" className="client-card__open" onClick={() => onSelect(client)} aria-label={t('clientCard.openClient', { name: client.name })} />
      <div className="client-card__content">
        <div className="client-card__heading">
          <span className="client-card__avatar" aria-hidden="true">{initials}</span>
          <div className="client-card__identity">
            <h2>{client.name}</h2>
            <p><Phone size={13} aria-hidden="true" />{phoneIssue(client.phone) ? client.phone : formatPhone(client.phone)}</p>
          </div>
          <ArrowUpRight className="client-card__arrow" size={19} aria-hidden="true" />
        </div>
        <div className="client-card__tags">
          {visibleTags.map(tag => <span key={tag} className={`tag-badge ${tag.toLowerCase() === 'vip' ? 'tag-vip' : /sensitive|чувствитель/i.test(tag) ? 'tag-sensitive' : ''}`}>{tag}</span>)}
          {remainingTags.length > 0 && <span className="client-card__tag-overflow"><span aria-hidden="true">+{remainingTags.length}</span><span className="sr-only">{remainingTags.join(', ')}</span></span>}
        </div>
      </div>
      <div className="client-card__footer">
        <div className="client-card__meta"><strong>{t('counts.visits', { count: client.totalVisits })}</strong><span><Calendar size={12} aria-hidden="true" />{t('clients.updated', { date: formatDate(client.updatedAt) })}</span></div>
        <button type="button" onClick={() => onLogVisit(client)} className="btn-secondary"><Plus size={15} aria-hidden="true" />{t('clientCard.logVisit')}</button>
      </div>
    </article>
  );
}
