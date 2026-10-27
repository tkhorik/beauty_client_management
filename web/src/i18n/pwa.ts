export const pwaEn = {
  'pwa.install': 'Install web app',
  'pwa.iosTitle': 'Install Aura Beauty Log',
  'pwa.iosSteps': 'In Safari, tap Share, choose Add to Home Screen, keep Open as Web App enabled if it is shown, then tap Add.',
  'pwa.iosSafari': 'If Add to Home Screen is unavailable, open this page in Safari.',
  'pwa.settingsTitle': 'Install web app',
  'pwa.settingsDescription': 'Add Aura Beauty Log to your device for an app-like window. Client records still require an internet connection.',
  'pwa.offline': 'You are offline. Client work needs internet. Changes are not queued for later.',
  'pwa.updateAvailable': 'An app update is available.',
  'pwa.updateConfirm': 'Reloading may discard unsaved changes. Update now?',
  'pwa.update': 'Update',
  'pwa.reload': 'Reload',
  'pwa.later': 'Later',
  'pwa.updateFailed': 'Update failed. Please try again later.',
} as const;

export type PwaKey = keyof typeof pwaEn;
export const pwaRu: Record<PwaKey, string> = {
  'pwa.install': 'Установить веб-приложение',
  'pwa.iosTitle': 'Установить Aura Beauty Log',
  'pwa.iosSteps': 'В Safari нажмите «Поделиться», затем «На экран Домой». Оставьте «Открывать как веб-приложение» включённым, если этот переключатель есть, и нажмите «Добавить».',
  'pwa.iosSafari': 'Если пункта «На экран Домой» нет, откройте эту страницу в Safari.',
  'pwa.settingsTitle': 'Установить веб-приложение',
  'pwa.settingsDescription': 'Добавьте Aura Beauty Log на устройство, чтобы открывать его в отдельном окне. Для работы с клиентами нужен интернет.',
  'pwa.offline': 'Нет подключения. Для работы с клиентами нужен интернет. Изменения не ставятся в очередь на отправку.',
  'pwa.updateAvailable': 'Доступно обновление приложения.',
  'pwa.updateConfirm': 'Перезагрузка может удалить несохранённые изменения. Обновить сейчас?',
  'pwa.update': 'Обновить',
  'pwa.reload': 'Перезагрузить',
  'pwa.later': 'Позже',
  'pwa.updateFailed': 'Не удалось обновить. Повторите позже.',
};
