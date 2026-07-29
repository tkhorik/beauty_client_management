/**
 * Password rules shared by every web form that sets a password
 * (`LoginPage`'s registration form and `SettingsModal`'s change-password
 * form), kept in one place so the two can't quietly drift apart.
 *
 * These mirror `Validation.kt` on the backend — see the comment there. The
 * server is the authority; this exists only so the user finds out about a
 * too-short password before a round trip, not instead of the server check.
 */
export const PASSWORD_MIN_LENGTH = 12;
export const PASSWORD_MAX_BYTES = 72;

/** BCrypt measures its 72-byte limit in bytes, not characters. */
export function byteLength(value: string): number {
  return new TextEncoder().encode(value).length;
}

/** Returns a user-facing message for an invalid password, or null when it's fine. */
export function validatePasswordLocally(password: string): string | null {
  if (password.length < PASSWORD_MIN_LENGTH) {
    return `Password must be at least ${PASSWORD_MIN_LENGTH} characters.`;
  }
  if (byteLength(password) > PASSWORD_MAX_BYTES) {
    return `Password must be at most ${PASSWORD_MAX_BYTES} bytes long.`;
  }
  return null;
}
