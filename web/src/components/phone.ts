/**
 * Client-side mirror of the backend's phone rule (`Validation.phoneIssue` /
 * `formatPhone` in `backend/.../validation/Validation.kt`), also mirrored by
 * Android's `AuthValidation.kt`. Change all three together.
 */

/** Country code -> digit groups after it. Every supported number is 11 digits. */
const PHONE_GROUPS: Record<string, number[]> = { '1': [3, 3, 4], '7': [3, 3, 2, 2] };
const SEPARATORS = [' (', ') ', '-', '-'];

export const PHONE_DIGITS = 11;
export const PHONE_PLACEHOLDER = '+1 (555) 000-0000';

export type PhoneIssue = 'PHONE_REQUIRED' | 'PHONE_INVALID';

/**
 * Digits with the country code resolved: a leading 8 is Russia's domestic
 * trunk prefix for +7, and any other unknown first digit means the user
 * skipped the country code, so +1 is assumed.
 */
export function phoneDigits(raw: string): string {
  const digits = raw.replace(/\D/g, '');
  if (!digits) return '';
  if (digits[0] === '8') return '7' + digits.slice(1);
  if (!Object.hasOwn(PHONE_GROUPS, digits[0])) return '1' + digits;
  return digits;
}

/**
 * Masks however many digits there are. A separator is only written once a
 * digit follows it, so "+1 (" never appears and backspace cannot get stuck
 * re-adding a bracket it just removed.
 */
export function formatPhone(raw: string): string {
  const digits = phoneDigits(raw).slice(0, PHONE_DIGITS);
  if (!digits) return '';
  let out = '+' + digits[0];
  let rest = digits.slice(1);
  PHONE_GROUPS[digits[0]].forEach((size, i) => {
    if (!rest) return;
    out += SEPARATORS[i] + rest.slice(0, size);
    rest = rest.slice(size);
  });
  return out;
}

/** Length of a complete masked number, for the input's `maxLength`. */
export function maskedLength(value: string): number {
  return formatPhone(value.startsWith('+1') ? '1'.repeat(PHONE_DIGITS) : '7'.repeat(PHONE_DIGITS)).length;
}

export function phoneIssue(phone: string): PhoneIssue | null {
  const digits = phoneDigits(phone);
  if (!digits) return 'PHONE_REQUIRED';
  return digits.length === PHONE_DIGITS ? null : 'PHONE_INVALID';
}
