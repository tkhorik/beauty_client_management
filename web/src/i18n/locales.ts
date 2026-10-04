/**
 * Application-owned copy.  Values supplied by a salon (names, notes, tags and
 * custom field labels) deliberately do not belong here: translating them would
 * change customer data rather than the interface around it.
 */
export const en = {
  language: { system: 'System default', en: 'English', ru: 'Русский', label: 'Language', conflict: 'A newer language setting from another device was applied.' },
  common: {
    appName: 'Aura Beauty Log', loading: 'Loading…', save: 'Save', cancel: 'Cancel', close: 'Close',
    delete: 'Delete', edit: 'Edit', create: 'Create', error: 'Something went wrong. Please try again.',
    network: 'Server could not be reached. Make sure the backend is running.',
  },
  auth: {
    signIn: 'Sign In', signOut: 'Sign out', createAccount: 'Create Account', createYourAccount: 'Create your account',
    signInAccount: 'Sign in to your account', fullName: 'Full Name', email: 'Email', password: 'Password',
    confirmPassword: 'Confirm Password', forgotPassword: 'Forgot password?', firstTime: 'First time here?',
    alreadyAccount: 'Already have an account?', hidePassword: 'Hide password', showPassword: 'Show password',
    pleaseWait: 'Please wait…', nameRequired: 'Name is required.', passwordsMismatch: 'Passwords do not match.',
    invalidCredentials: 'Invalid credentials.', duplicateEmail: 'An account with this email already exists.',
    tooManyAttempts: 'Too many attempts. Please wait a moment and try again.', checkDetails: 'Please check the details you entered.',
  },
  settings: {
    title: 'Account Settings', profile: 'Profile', saveName: 'Save Name', profileUpdated: 'Profile updated.',
    languageDescription: 'Choose the language used by Aura Beauty Log.', currentPasswordRequired: 'Enter your current password.',
  },
  clients: {
    loading: 'Loading Beauty Client Directory & Procedure Logs…', none: 'No Client Profiles Found',
    noMatch: 'No clients match your filter criteria.', first: 'Get started by creating your first beauty client record.',
    create: 'Create Client Profile', updated: 'Updated {{date}}',
  },
  counts: {
    visits_one: '{{count}} visit', visits_other: '{{count}} visits',
    activeProfiles_one: '{{count}} active profile', activeProfiles_other: '{{count}} active profiles',
    days_one: '{{count}} day', days_other: '{{count}} days',
    visits_few: '{{count}} visits', visits_many: '{{count}} visits',
    activeProfiles_few: '{{count}} active profiles', activeProfiles_many: '{{count}} active profiles',
    days_few: '{{count}} days', days_many: '{{count}} days',
  },
  errors: {
    EMAIL_NOT_VERIFIED: 'Your changes were not saved. Confirm your email address first.',
    VALIDATION_ERROR: 'Please check the details you entered.', UNKNOWN: 'Something went wrong. Please try again.',
  },
} as const;

type TranslationShape<T> = { [K in keyof T]: T[K] extends string ? string : TranslationShape<T[K]> };

export const ru: TranslationShape<typeof en> = {
  language: { system: 'Системный язык', en: 'English', ru: 'Русский', label: 'Язык', conflict: 'Применена более новая настройка языка с другого устройства.' },
  common: {
    appName: 'Aura Beauty Log', loading: 'Загрузка…', save: 'Сохранить', cancel: 'Отмена', close: 'Закрыть',
    delete: 'Удалить', edit: 'Изменить', create: 'Создать', error: 'Что-то пошло не так. Попробуйте ещё раз.',
    network: 'Не удалось подключиться к серверу. Убедитесь, что сервер запущен.',
  },
  auth: {
    signIn: 'Войти', signOut: 'Выйти', createAccount: 'Создать аккаунт', createYourAccount: 'Создайте аккаунт',
    signInAccount: 'Войдите в свой аккаунт', fullName: 'Полное имя', email: 'Эл. почта', password: 'Пароль',
    confirmPassword: 'Подтвердите пароль', forgotPassword: 'Забыли пароль?', firstTime: 'Впервые здесь?',
    alreadyAccount: 'Уже есть аккаунт?', hidePassword: 'Скрыть пароль', showPassword: 'Показать пароль',
    pleaseWait: 'Подождите…', nameRequired: 'Укажите имя.', passwordsMismatch: 'Пароли не совпадают.',
    invalidCredentials: 'Неверный адрес или пароль.', duplicateEmail: 'Аккаунт с этим адресом уже существует.',
    tooManyAttempts: 'Слишком много попыток. Подождите немного и повторите.', checkDetails: 'Проверьте введённые данные.',
  },
  settings: {
    title: 'Настройки аккаунта', profile: 'Профиль', saveName: 'Сохранить имя', profileUpdated: 'Профиль обновлён.',
    languageDescription: 'Выберите язык интерфейса Aura Beauty Log.', currentPasswordRequired: 'Введите текущий пароль.',
  },
  clients: {
    loading: 'Загрузка клиентов и журналов процедур…', none: 'Карточки клиентов не найдены',
    noMatch: 'Нет клиентов, соответствующих фильтру.', first: 'Начните с создания первой карточки клиента.',
    create: 'Создать карточку клиента', updated: 'Обновлено: {{date}}',
  },
  counts: {
    visits_one: '{{count}} процедура', visits_other: '{{count}} процедур',
    activeProfiles_one: '{{count}} активная карточка', activeProfiles_other: '{{count}} активных карточек',
    days_one: '{{count}} день', days_other: '{{count}} дней',
    visits_few: '{{count}} процедуры', visits_many: '{{count}} процедур',
    activeProfiles_few: '{{count}} активные карточки', activeProfiles_many: '{{count}} активных карточек',
    days_few: '{{count}} дня', days_many: '{{count}} дней',
  },
  errors: {
    EMAIL_NOT_VERIFIED: 'Изменения не сохранены. Сначала подтвердите адрес электронной почты.',
    VALIDATION_ERROR: 'Проверьте введённые данные.', UNKNOWN: 'Что-то пошло не так. Попробуйте ещё раз.',
  },
};

import type { FeatureKey } from './features';
import type { ApiErrorCode } from './apiErrors';
import type { RuntimeKey } from './runtimeFeatures';
import type { NewClientKey } from './newClient';

import type { AuditKey } from './audit';

export type TranslationKey = AuditKey | FeatureKey | RuntimeKey | NewClientKey | `apiErrors.${ApiErrorCode}`
  | 'language.system' | 'language.en' | 'language.ru' | 'language.label' | 'language.conflict'
  | 'common.appName' | 'common.loading' | 'common.save' | 'common.cancel' | 'common.close' | 'common.delete' | 'common.edit' | 'common.create' | 'common.error' | 'common.network'
  | 'auth.signIn' | 'auth.signOut' | 'auth.createAccount' | 'auth.createYourAccount' | 'auth.signInAccount' | 'auth.fullName' | 'auth.email' | 'auth.password' | 'auth.confirmPassword' | 'auth.forgotPassword' | 'auth.firstTime' | 'auth.alreadyAccount' | 'auth.hidePassword' | 'auth.showPassword' | 'auth.pleaseWait' | 'auth.nameRequired' | 'auth.passwordsMismatch' | 'auth.invalidCredentials' | 'auth.duplicateEmail' | 'auth.tooManyAttempts' | 'auth.checkDetails'
  | 'settings.title' | 'settings.profile' | 'settings.saveName' | 'settings.profileUpdated' | 'settings.languageDescription' | 'settings.currentPasswordRequired'
  | 'clients.loading' | 'clients.none' | 'clients.noMatch' | 'clients.first' | 'clients.create' | 'clients.updated'
  | 'counts.visits' | 'counts.activeProfiles' | 'counts.days'
  | 'errors.EMAIL_NOT_VERIFIED' | 'errors.VALIDATION_ERROR' | 'errors.UNKNOWN';
