import type { LanguagePreference } from './store';

export type LanguagePending = { id: string; preference: LanguagePreference; revision: number };

/** Returns null only when the response belongs to the current pending edit. */
export function settlePending(request: LanguagePending, current: LanguagePending | null, serverRevision: number): LanguagePending | null {
  if (!current || current.id === request.id) return null;
  // Keep a newer click and rebase it on the successful request revision.
  return { ...current, revision: serverRevision };
}

/** A response may mutate state only while the same auth lifecycle is active. */
export function responseBelongsToSession(requestUserId: string, requestGeneration: number, currentUserId: string | undefined, currentGeneration: number): boolean {
  return requestUserId === currentUserId && requestGeneration === currentGeneration;
}
