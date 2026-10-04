import i18n from 'i18next';
import type { ApiError } from '../services/api';
import { isApiErrorCode } from './apiErrors';
import type { TranslationKey } from './locales';

type ErrorBody = Pick<ApiError, 'body'> | { body?: ApiError['body'] };

/** Stable API codes are the sole input to error translation. */
export function errorTranslationKey(error: ErrorBody): TranslationKey {
  const code = error.body?.code;
  if (isApiErrorCode(code)) return `apiErrors.${code}`;
  return 'errors.UNKNOWN';
}

export function translatedApiError(error: ErrorBody, fallback?: string): string {
  const key = errorTranslationKey(error);
  if (key === 'errors.UNKNOWN' && fallback) return fallback;
  return i18n.t(key, error.body?.fieldErrors ? Object.values(error.body.fieldErrors)[0]?.args : undefined);
}

export function translatedFieldErrors(body: ApiError['body']): Record<string, string> {
  if (!body.fieldErrors) return {};
  return Object.fromEntries(Object.entries(body.fieldErrors).map(([field, issue]) => [
    field,
    i18n.t(isApiErrorCode(issue.code) ? `apiErrors.${issue.code}` : 'errors.VALIDATION_ERROR', issue.args),
  ]));
}
