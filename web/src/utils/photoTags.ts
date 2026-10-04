import type { Attachment } from '../types';

/**
 * The first BEFORE/AFTER slot still free on a visit, so the usual flow — a
 * baseline shot at the start, the result at the end — needs no extra click.
 * Mirrors Android's `defaultPhotoTag`.
 */
export const defaultPhotoTag = (attachments: Attachment[]): Attachment['tag'] => {
  const tags = new Set(attachments.map(a => a.tag));
  if (!tags.has('BEFORE')) return 'BEFORE';
  if (!tags.has('AFTER')) return 'AFTER';
  return 'PROCEDURE';
};
