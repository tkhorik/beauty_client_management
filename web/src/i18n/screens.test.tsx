// @vitest-environment jsdom
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import i18n from 'i18next';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LocaleProvider } from './LocaleProvider';
import { LoginPage } from '../components/LoginPage';
import { ForgotPasswordPage } from '../components/ForgotPasswordPage';
import { ResetPasswordPage } from '../components/ResetPasswordPage';
import { VerifyEmailPage } from '../components/VerifyEmailPage';
import { SettingsModal } from '../components/SettingsModal';
import { OrganizationOnboarding } from '../components/OrganizationOnboarding';
import { Header } from '../components/Header';
import { ClientCard } from '../components/ClientCard';
import { ClientDetailModal } from '../components/ClientDetailModal';
import { NewClientModal } from '../components/NewClientModal';
import { EditClientModal } from '../components/EditClientModal';
import { NewVisitModal } from '../components/NewVisitModal';
import { PhotoCompareModal } from '../components/PhotoCompareModal';
import { MembersModal } from '../components/MembersModal';
import { AdminPanel } from '../components/AdminPanel';
import { VerificationWall } from '../components/VerificationWall';
import { VerificationBanner } from '../components/VerificationBanner';
import type { Attachment, Client, Visit } from '../types';

const noop = () => {};
vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({
  user: { id: 'a', fullName: 'Customer Name', email: 'customer@example.com', languagePreference: 'en', languageRevision: 1, globalRole: 'SUPER_ADMIN', emailVerified: false, verificationDeadline: new Date(Date.now() + 2 * 86_400_000).toISOString() },
  token: 'test-only-token', logout: () => {}, updateUser: () => {},
}) }));
vi.mock('../auth/OrgContext', () => ({ useOrg: () => ({
  current: { id: 'org', name: 'Customer Salon', role: 'ORG_ADMIN' }, organizations: [], activeOrganizations: [{ id: 'org', name: 'Customer Salon' }], refresh: async () => {}, select: () => {}, offline: false,
}) }));

const client: Client = { id: 'c', name: 'Customer Name', phone: '+79991234567', email: 'customer@example.com', tags: ['Customer tag'], customFields: { 'Customer field': 'Customer value' }, totalVisits: 21, updatedAt: '2026-10-03T12:00:00Z', createdAt: '2026-01-01T12:00:00Z' };
const attachments: Attachment[] = [
  { id: 'before', visitId: 'v', fileUrl: '/before.jpg', fileType: 'image/jpeg', fileSize: 10, tag: 'BEFORE', uploadedAt: client.updatedAt },
  { id: 'after', visitId: 'v', fileUrl: '/after.jpg', fileType: 'image/jpeg', fileSize: 10, tag: 'AFTER', uploadedAt: client.updatedAt },
];
const visits: Visit[] = [{ id: 'v', clientId: 'c', visitDateTime: client.updatedAt, durationMinutes: 21, procedureNotes: 'Customer notes', status: 'COMPLETED', attachments, createdAt: client.createdAt }];
const cases: [string, () => ReactElement, string, string][] = [
  ['login', () => <LoginPage />, 'Sign In', 'Войти'],
  ['password recovery', () => <ForgotPasswordPage />, 'Reset your password', 'Сбросить пароль'],
  ['reset password', () => <ResetPasswordPage token="test" />, 'Choose a new password', 'Выберите новый пароль'],
  ['email verified', () => <VerifyEmailPage status="success" />, 'Email verified', 'Адрес подтверждён'],
  ['email verification failure', () => <VerifyEmailPage status="invalid" />, "That link didn&#x27;t work", 'Ссылка не сработала'],
  ['settings', () => <SettingsModal onClose={noop} />, 'Account Settings', 'Настройки аккаунта'],
  ['organization onboarding', () => <OrganizationOnboarding onOpenAdmin={noop} />, 'Choose an organization', 'Выберите организацию'],
  ['header', () => <Header searchQuery="" onSearchChange={noop} selectedTag="" onTagSelect={noop} onOpenNewClient={noop} onOpenNewVisit={noop} onOpenSettings={noop} onOpenMembers={noop} onOpenAdmin={noop} totalClients={21} />, '21 active profiles', '21 активная карточка'],
  ['client directory card', () => <ClientCard client={client} onSelect={noop} onLogVisit={noop} />, '21 visits', '21 процедура'],
  ['client details', () => <ClientDetailModal client={client} visits={visits} onClose={noop} onRefresh={noop} onOpenNewVisit={noop} onOpenPhotoCompare={noop} />, 'COMPLETED', 'ЗАВЕРШЕНА'],
  ['new client', () => <NewClientModal onClose={noop} onSuccess={noop} />, 'Create New Client Profile', 'Создать карточку клиента'],
  ['edit client', () => <EditClientModal client={client} onClose={noop} onSuccess={noop} />, 'Edit Client Profile', 'Изменить карточку клиента'],
  ['new procedure', () => <NewVisitModal client={client} clientsList={[client]} onClose={noop} onSuccess={noop} />, 'Log Procedure Visit Entry', 'Записать процедуру'],
  ['photo comparison', () => <PhotoCompareModal attachments={attachments} onClose={noop} />, 'Procedure Before &amp; After Comparison', 'Сравнение до и после процедуры'],
  ['organization members', () => <MembersModal orgId="org" orgName="Customer Salon" onClose={noop} />, 'Invite someone', 'Пригласить участника'],
  ['administration', () => <AdminPanel onClose={noop} />, 'Users (0)', 'Пользователи (0)'],
  ['verification wall', () => <VerificationWall />, 'Confirm your email', 'Подтвердите адрес'],
  ['verification banner', () => <VerificationBanner />, 'within 2 days', 'в течение 2 дней'],
];

beforeEach(() => { localStorage.clear(); window.history.replaceState({}, '', '/'); });
for (const language of ['en', 'ru'] as const) {
  describe(`${language} screen SSR`, () => {
    for (const [name, element, english, russian] of cases) {
      it(`renders ${name} using catalog messages`, async () => {
        localStorage.setItem('beauty:locale-preference', language);
        await i18n.changeLanguage(language);
        const markup = renderToStaticMarkup(<LocaleProvider>{element()}</LocaleProvider>);
        expect(markup).toContain(language === 'ru' ? russian : english);
        expect(markup).not.toMatch(/(?:newClientModal|runtime|apiErrors|verificationBanner)\.[A-Za-z]/);
        if (name === 'client details' || name === 'edit client' || name === 'client directory card') {
          expect(markup).toContain('Customer field');
          expect(markup).toContain('Customer value');
          if (name !== 'client details') expect(markup).toContain('Customer tag');
        }
        if (name === 'client directory card') expect(markup).toContain(new Intl.DateTimeFormat(language).format(new Date(client.updatedAt)));
        if (name === 'client details') expect(markup).toContain(language === 'ru' ? '21 минута' : '21 minutes');
        if (name === 'settings' || name === 'new client' || name === 'client details') expect(markup).toContain(`aria-label="${language === 'ru' ? 'Закрыть' : 'Close'}"`);
      });
    }
  });
}
