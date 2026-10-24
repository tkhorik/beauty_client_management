export const webUxEn = {
  'webUx.discardDraft': 'Discard unsaved changes? Choose Cancel to keep editing.',
  'webUx.optionalDetails': 'Additional details (optional)',
  'webUx.clientDetails': 'Client details',
  'webUx.moreActions': 'More client actions',
  'webUx.deleting': 'Deleting…',
} as const;
export const webUxRu: { [K in keyof typeof webUxEn]: string } = {
  'webUx.discardDraft': 'Удалить несохранённые изменения? Нажмите «Отмена», чтобы продолжить редактирование.',
  'webUx.optionalDetails': 'Дополнительные сведения (необязательно)',
  'webUx.clientDetails': 'Сведения о клиенте',
  'webUx.moreActions': 'Другие действия с клиентом',
  'webUx.deleting': 'Удаление…',
};
export type WebUxKey = keyof typeof webUxEn;
