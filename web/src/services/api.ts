import type {
  Client,
  Visit,
  Attachment,
  CreateClientInput,
  CreateVisitInput,
  UserProfile,
  Organization,
  OrgMember,
  OrgRole,
  AdminUser,
  AdminOrganization,
  OrganizationCreationLink,
  CreateCreationLinkResult,
} from '../types';
import { getToken, clearToken } from '../auth/tokenStore';
import { getActiveOrgId } from '../auth/orgStore';
import { refreshAccessToken, type SessionResult } from '../auth/session';
import { API_BASE_URL } from '../config';

/**
 * Names the organization a request is scoped to.
 *
 * Must match `ORG_HEADER` in `backend/.../plugins/OrgAccess.kt` and the CORS
 * allowlist in `Routing.kt`. A mismatch shows up as a preflight failure that
 * says nothing about organizations, so the three move together.
 */
export const ORG_HEADER = 'X-Org-Id';

/**
 * Mock persistence is intentionally opt-in.  It is useful for a product demo,
 * but must never make a rejected production write look as if it was saved.
 */
const DEMO_MODE = import.meta.env.VITE_DEMO_MODE === 'true';

/**
 * Carries the parsed error body (field-level messages, or a flat `error`
 * string) alongside the HTTP status, so callers can render the same kind of
 * inline, per-field feedback `LoginPage` gives on registration.
 */
export class ApiError extends Error {
  status: number;
  body: { error?: string; errors?: Record<string, string> };

  constructor(status: number, body: { error?: string; errors?: Record<string, string> }) {
    super(body.error ?? 'Request failed');
    this.status = status;
    this.body = body;
  }
}

/**
 * The backend refused a write because the account's address is unconfirmed.
 *
 * A distinct type, not just an `ApiError` with a 403, because of how the rest
 * of this class handles failure: every data method falls back to localStorage
 * when the backend call throws. That fallback exists for offline/demo mode and
 * is correct for a network failure — but applying it here would write the
 * record into localStorage and hand the UI a success, telling the user their
 * client was saved when the server just refused it. A refusal is not an outage.
 * Every fallback path below rethrows this rather than swallowing it.
 */
export class EmailNotVerifiedError extends ApiError {
  /** When the grace window closed, if the server said. */
  deadline: string | null;

  constructor(body: { error?: string; verificationDeadline?: string | null }) {
    super(403, body);
    this.name = 'EmailNotVerifiedError';
    this.deadline = body.verificationDeadline ?? null;
  }
}

/** Fired when any request is refused for want of a confirmed address. */
export const EMAIL_UNVERIFIED_EVENT = 'beauty:email-unverified';

/**
 * A message to show the user when a save fails.
 *
 * Exists so the write dialogs do not all answer "Failed to create client
 * profile" to a refusal that has a specific, actionable cause. A user told only
 * that something failed will retry it, get the same result, and conclude the
 * app is broken — when the fix is a link sitting in their inbox.
 */
export function writeErrorMessage(err: unknown, fallback: string): string {
  if (err instanceof EmailNotVerifiedError) {
    return 'Your changes were not saved. Confirm your email address first — see the banner at the top of the page for a fresh link.';
  }
  return fallback;
}

// Initial Mock Data for instant demonstration & fallback
const INITIAL_MOCK_CLIENTS: Client[] = [
  {
    id: 'client-1',
    name: 'Elena Vance',
    phone: '+1 (555) 234-5678',
    email: 'elena.vance@example.com',
    tags: ['VIP', 'Sensitive Skin', 'Lash Extensions'],
    customFields: {
      'Skin Type': 'Combination / Sensitive',
      'Allergies': 'Latex, Fragrance',
      'Preferred Color Code': '#D4A373 (Warm Amber)',
      'Lash Mapping': 'Cat Eye 10-14mm C-Curl'
    },
    totalVisits: 3,
    createdAt: '2026-06-10T10:00:00Z',
    updatedAt: '2026-07-22T14:30:00Z'
  },
  {
    id: 'client-2',
    name: 'Sophia Reynolds',
    phone: '+1 (555) 876-5432',
    email: 'sophia.r@example.com',
    tags: ['Hair Coloring', 'VIP'],
    customFields: {
      'Hair Formula Ratio': 'Developer 20vol : Dye 1:1.5',
      'Scalp Sensitivity': 'Low',
      'Tone Preference': 'Ash Blonde 9.1'
    },
    totalVisits: 2,
    createdAt: '2026-06-15T11:20:00Z',
    updatedAt: '2026-07-20T16:45:00Z'
  },
  {
    id: 'client-3',
    name: 'Chloe Bennett',
    phone: '+1 (555) 432-1098',
    email: 'chloe.b@example.com',
    tags: ['Skin Treatment'],
    customFields: {
      'Treatment Intensity': 'Level 3 Microdermabrasion',
      'Hydration Specs': 'Hyaluronic Serum 5ml'
    },
    totalVisits: 1,
    createdAt: '2026-07-01T09:15:00Z',
    updatedAt: '2026-07-01T09:15:00Z'
  }
];

const INITIAL_MOCK_VISITS: Visit[] = [
  {
    id: 'visit-101',
    clientId: 'client-1',
    visitDateTime: '2026-07-22T14:00:00Z',
    durationMinutes: 75,
    procedureNotes: 'Volume Lash Full Set (Cat Eye style). Applied C-Curl 0.07mm extensions ranging from 10mm inner corner to 14mm outer corner. Used Sensitive Adhesive (Latex-free). Client experienced zero irritation.',
    status: 'COMPLETED',
    attachments: [
      {
        id: 'att-1',
        visitId: 'visit-101',
        fileUrl: 'https://images.unsplash.com/photo-1522337360788-8b13dee7a37e?auto=format&fit=crop&w=800&q=80',
        fileType: 'image/jpeg',
        fileSize: 450000,
        caption: 'Natural lashes prior to extension application',
        tag: 'BEFORE',
        uploadedAt: '2026-07-22T14:05:00Z'
      },
      {
        id: 'att-2',
        visitId: 'visit-101',
        fileUrl: 'https://images.unsplash.com/photo-1512496015851-a90fb38ba796?auto=format&fit=crop&w=800&q=80',
        fileType: 'image/jpeg',
        fileSize: 520000,
        caption: 'Full volume lash extensions completion',
        tag: 'AFTER',
        uploadedAt: '2026-07-22T15:15:00Z'
      }
    ],
    createdAt: '2026-07-22T14:00:00Z'
  },
  {
    id: 'visit-102',
    clientId: 'client-1',
    visitDateTime: '2026-06-25T11:00:00Z',
    durationMinutes: 60,
    procedureNotes: 'Lash Refill & Hydrating Eye Mask treatment. Replaced 40% lash extensions on left eye and 45% on right eye.',
    status: 'COMPLETED',
    attachments: [],
    createdAt: '2026-06-25T11:00:00Z'
  },
  {
    id: 'visit-103',
    clientId: 'client-2',
    visitDateTime: '2026-07-20T15:30:00Z',
    durationMinutes: 120,
    procedureNotes: 'Root touch-up & Gloss treatment. Used 30g Formula 8.1 + 45g 20vol Matrix developer. Processed for 35 minutes.',
    status: 'COMPLETED',
    attachments: [
      {
        id: 'att-3',
        visitId: 'visit-103',
        fileUrl: 'https://images.unsplash.com/photo-1560869713-7d0a29430803?auto=format&fit=crop&w=800&q=80',
        fileType: 'image/jpeg',
        fileSize: 610000,
        caption: 'Hair color tone & shine post-treatment',
        tag: 'AFTER',
        uploadedAt: '2026-07-20T17:30:00Z'
      }
    ],
    createdAt: '2026-07-20T15:30:00Z'
  }
];

class ApiService {
  /** Object URLs let `<img>` use authenticated attachment responses without putting a token in a URL. */
  private attachmentObjectUrls = new Map<string, string>();

  /**
   * Older visit records may predate the attachments field.  Keep the API
   * boundary backwards-compatible so consumers can always treat it as an
   * array.
   */
  private normalizeVisit(visit: Visit): Visit {
    return {
      ...visit,
      attachments: Array.isArray(visit.attachments) ? visit.attachments : []
    };
  }

  /**
   * Wraps fetch() with Bearer token injection, a transparent one-shot refresh
   * on expiry, and central 401 handling.
   *
   * Access tokens now last minutes rather than forever, so a 401 mid-session is
   * the expected case, not an error: it usually means "expired", not "invalid".
   * Refreshing and retrying once turns that into something the user never sees.
   * Only a 401 that survives the retry means the session is genuinely over.
   */
  private async authFetch(input: RequestInfo, init: RequestInit = {}): Promise<Response> {
    const send = (token: string | null) => {
      const headers = new Headers(init.headers);
      if (token) headers.set('Authorization', `Bearer ${token}`);

      // Every data request is scoped to one organization, and the header is
      // attached here rather than at each call site so a new endpoint cannot
      // be added without it. The organization endpoints themselves override
      // this with an explicit value or omit it — see `orgFetch`.
      const orgId = getActiveOrgId();
      if (orgId && !headers.has(ORG_HEADER)) headers.set(ORG_HEADER, orgId);

      return fetch(input, { ...init, headers });
    };

    let res = await send(getToken());
    if (res.status === 403) return await this.rejectIfUnverified(res);
    if (res.status !== 401) return res;

    // Concurrent 401s all await the same refresh — see the note on
    // single-flight in auth/session.ts. Starting one refresh per request would
    // look like refresh-token reuse and log the user out.
    const refreshed = await refreshAccessToken();
    if (refreshed) {
      res = await send(refreshed.token);
      if (res.status === 403) return await this.rejectIfUnverified(res);
      if (res.status !== 401) return res;
    }

    clearToken();
    window.dispatchEvent(new Event('beauty:unauthorized'));
    return res;
  }

  /**
   * Turns a 403 `EMAIL_NOT_VERIFIED` into a thrown [EmailNotVerifiedError],
   * and passes every other 403 through untouched.
   *
   * Deliberately kept apart from the 401 path above. A 401 means the session is
   * over and ends with `beauty:unauthorized`, which logs the user out; routing
   * this through the same branch would sign out a perfectly valid session over
   * an unread confirmation email — a far more confusing outcome than the
   * restriction itself.
   *
   * The response is cloned before reading, because the caller may still want to
   * read the body of a 403 this method decides not to claim (`NOT_A_MEMBER`,
   * `ADMIN_REQUIRED`), and a body can only be consumed once.
   */
  private async rejectIfUnverified(res: Response): Promise<Response> {
    const body = await res.clone().json().catch(() => ({} as Record<string, unknown>));
    if (body?.code !== 'EMAIL_NOT_VERIFIED') return res;

    window.dispatchEvent(
      new CustomEvent(EMAIL_UNVERIFIED_EVENT, {
        detail: { deadline: body.verificationDeadline ?? null },
      })
    );
    throw new EmailNotVerifiedError(body);
  }

  /**
   * Rethrows a verification refusal instead of letting it fall through to the
   * localStorage path.
   *
   * Called from the `catch` of every method that has a fallback. Without it,
   * the app would report a save that never happened — see the note on
   * [EmailNotVerifiedError].
   */
  private rethrowIfBlocked(err: unknown): void {
    if (err instanceof EmailNotVerifiedError) throw err;
  }

  /** Converts an HTTP refusal into an error rather than silently using mocks. */
  private async apiError(res: Response): Promise<ApiError> {
    const body = await res.json().catch(() => ({}));
    return new ApiError(res.status, body);
  }

  /** Only a deliberate demo session may fall back after a network failure. */
  private fallbackOrThrow(err: unknown): void {
    this.rethrowIfBlocked(err);
    if (!DEMO_MODE || err instanceof ApiError) throw err;
  }

  private async hydrateAttachment(attachment: Attachment): Promise<Attachment> {
    if (!attachment.fileUrl.startsWith('/api/attachments/')) return attachment;

    const cached = this.attachmentObjectUrls.get(attachment.id);
    if (cached) return { ...attachment, fileUrl: cached };

    const res = await this.authFetch(new URL(attachment.fileUrl, API_BASE_URL).toString());
    if (!res.ok) throw await this.apiError(res);
    const objectUrl = URL.createObjectURL(await res.blob());
    this.attachmentObjectUrls.set(attachment.id, objectUrl);
    return { ...attachment, fileUrl: objectUrl };
  }

  private async hydrateVisitAttachments(visit: Visit): Promise<Visit> {
    const normalized = this.normalizeVisit(visit);
    return {
      ...normalized,
      attachments: await Promise.all(normalized.attachments.map(attachment => this.hydrateAttachment(attachment))),
    };
  }

  private getLocalClients(): Client[] {
    const saved = localStorage.getItem('beauty_clients');
    if (!saved) {
      localStorage.setItem('beauty_clients', JSON.stringify(INITIAL_MOCK_CLIENTS));
      return INITIAL_MOCK_CLIENTS;
    }
    return JSON.parse(saved);
  }

  private saveLocalClients(clients: Client[]) {
    localStorage.setItem('beauty_clients', JSON.stringify(clients));
  }

  private getLocalVisits(): Visit[] {
    const saved = localStorage.getItem('beauty_visits');
    if (!saved) {
      localStorage.setItem('beauty_visits', JSON.stringify(INITIAL_MOCK_VISITS));
      return INITIAL_MOCK_VISITS;
    }
    return JSON.parse(saved).map((visit: Visit) => this.normalizeVisit(visit));
  }

  private saveLocalVisits(visits: Visit[]) {
    localStorage.setItem('beauty_visits', JSON.stringify(visits));
  }

  async getClients(query?: string, tagFilter?: string): Promise<Client[]> {
    try {
      const url = new URL(`${API_BASE_URL}/clients`);
      if (query) url.searchParams.set('q', query);
      if (tagFilter) url.searchParams.set('tag', tagFilter);
      const res = await this.authFetch(url.toString());
      if (!res.ok) throw await this.apiError(res);
      return await res.json();
    } catch (err) {
      this.fallbackOrThrow(err);
    }

    let clients = this.getLocalClients();
    if (query) {
      const q = query.toLowerCase();
      clients = clients.filter(c => 
        c.name.toLowerCase().includes(q) ||
        c.phone.toLowerCase().includes(q) ||
        (c.email && c.email.toLowerCase().includes(q)) ||
        c.tags.some(t => t.toLowerCase().includes(q)) ||
        JSON.stringify(c.customFields).toLowerCase().includes(q)
      );
    }
    if (tagFilter) {
      const t = tagFilter.toLowerCase();
      clients = clients.filter(c => c.tags.some(tag => tag.toLowerCase() === t));
    }
    return clients;
  }

  async getClient(id: string): Promise<Client | null> {
    try {
      const res = await this.authFetch(`${API_BASE_URL}/clients/${id}`);
      if (res.ok) return await res.json();
      if (res.status === 404) return null;
      throw await this.apiError(res);
    } catch (err) {
      this.fallbackOrThrow(err);
    }
    const clients = this.getLocalClients();
    return clients.find(c => c.id === id) || null;
  }

  async createClient(input: CreateClientInput): Promise<Client> {
    try {
      const res = await this.authFetch(`${API_BASE_URL}/clients`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input)
      });
      if (!res.ok) throw await this.apiError(res);
      return await res.json();
    } catch (err) {
      this.fallbackOrThrow(err);
    }

    const clients = this.getLocalClients();
    const newClient: Client = {
      id: `client-${Date.now()}`,
      name: input.name,
      phone: input.phone,
      email: input.email,
      tags: input.tags,
      customFields: input.customFields,
      totalVisits: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };
    clients.unshift(newClient);
    this.saveLocalClients(clients);
    return newClient;
  }

  async updateClient(id: string, input: Partial<CreateClientInput>): Promise<Client> {
    try {
      const res = await this.authFetch(`${API_BASE_URL}/clients/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input)
      });
      if (!res.ok) throw await this.apiError(res);
      return await res.json();
    } catch (err) {
      this.fallbackOrThrow(err);
    }

    const clients = this.getLocalClients();
    const idx = clients.findIndex(c => c.id === id);
    if (idx === -1) throw new Error('Client not found');
    
    const updated: Client = {
      ...clients[idx],
      ...input,
      updatedAt: new Date().toISOString()
    };
    clients[idx] = updated;
    this.saveLocalClients(clients);
    return updated;
  }

  async deleteClient(id: string): Promise<void> {
    try {
      const res = await this.authFetch(`${API_BASE_URL}/clients/${id}`, { method: 'DELETE' });
      if (!res.ok) throw await this.apiError(res);
    } catch (err) {
      this.fallbackOrThrow(err);
    }

    const clients = this.getLocalClients().filter(c => c.id !== id);
    const visits = this.getLocalVisits().filter(v => v.clientId !== id);
    this.saveLocalClients(clients);
    this.saveLocalVisits(visits);
  }

  async getVisits(clientId?: string): Promise<Visit[]> {
    try {
      const url = new URL(`${API_BASE_URL}/visits`);
      if (clientId) url.searchParams.set('clientId', clientId);
      const res = await this.authFetch(url.toString());
      if (!res.ok) throw await this.apiError(res);
      const visits: Visit[] = await res.json();
      return await Promise.all(visits.map(visit => this.hydrateVisitAttachments(visit)));
    } catch (err) {
      this.fallbackOrThrow(err);
    }

    let visits = this.getLocalVisits();
    if (clientId) {
      visits = visits.filter(v => v.clientId === clientId);
    }
    return visits.sort((a, b) => new Date(b.visitDateTime).getTime() - new Date(a.visitDateTime).getTime());
  }

  async createVisit(input: CreateVisitInput): Promise<Visit> {
    try {
      const res = await this.authFetch(`${API_BASE_URL}/visits`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input)
      });
      if (!res.ok) throw await this.apiError(res);
      return await res.json();
    } catch (err) {
      this.fallbackOrThrow(err);
    }

    const visits = this.getLocalVisits();
    const newVisit: Visit = {
      id: `visit-${Date.now()}`,
      clientId: input.clientId,
      visitDateTime: input.visitDateTime,
      durationMinutes: input.durationMinutes,
      procedureNotes: input.procedureNotes,
      status: input.status,
      attachments: [],
      createdAt: new Date().toISOString()
    };
    visits.unshift(newVisit);
    this.saveLocalVisits(visits);

    // Increment client total visits count
    const clients = this.getLocalClients();
    const cIdx = clients.findIndex(c => c.id === input.clientId);
    if (cIdx !== -1) {
      clients[cIdx].totalVisits += 1;
      clients[cIdx].updatedAt = new Date().toISOString();
      this.saveLocalClients(clients);
    }

    return newVisit;
  }

  async addAttachment(visitId: string, fileDataUrl: string, tag: Attachment['tag'], caption?: string): Promise<Attachment> {
    try {
      const formData = new FormData();
      formData.append('visitId', visitId);
      formData.append('tag', tag);
      if (caption) formData.append('caption', caption);
      
      // Convert data url to blob
      const resBlob = await fetch(fileDataUrl);
      const blob = await resBlob.blob();
      formData.append('file', blob, 'photo.jpg');

      const res = await this.authFetch(`${API_BASE_URL}/attachments/upload`, {
        method: 'POST',
        body: formData
      });
      if (!res.ok) throw await this.apiError(res);
      return await this.hydrateAttachment(await res.json());
    } catch (err) {
      this.fallbackOrThrow(err);
    }

    const visits = this.getLocalVisits();
    const vIdx = visits.findIndex(v => v.id === visitId);
    const newAttachment: Attachment = {
      id: `att-${Date.now()}`,
      visitId,
      fileUrl: fileDataUrl,
      fileType: 'image/jpeg',
      fileSize: Math.round(fileDataUrl.length * 0.75),
      caption,
      tag,
      uploadedAt: new Date().toISOString()
    };

    if (vIdx !== -1) {
      visits[vIdx].attachments.push(newAttachment);
      this.saveLocalVisits(visits);
    }
    return newAttachment;
  }

  // -- Account settings ------------------------------------------------
  //
  // Deliberately no localStorage fallback below, unlike every other method
  // in this class. The offline/demo mode elsewhere exists so the app stays
  // usable with no backend; silently "succeeding" a profile edit or password
  // change into localStorage would tell the user their password changed when
  // it didn't touch the account that actually matters. These throw instead,
  // so the Settings UI can show a real error rather than a false confirmation.

  async getCurrentUser(): Promise<UserProfile> {
    const res = await this.authFetch(`${API_BASE_URL}/users/me`);
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new ApiError(res.status, body);
    }
    return res.json();
  }

  async updateProfile(fullName: string): Promise<UserProfile> {
    const res = await this.authFetch(`${API_BASE_URL}/users/me`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fullName }),
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new ApiError(res.status, body);
    }
    return res.json();
  }

  /**
   * Asks for a fresh verification link.
   *
   * Takes no address: the backend reads it from the access token, so this
   * cannot be used to mail a stranger. Answers 204 whether or not anything was
   * sent — including for an already-verified account — so there is nothing to
   * parse and nothing the caller could usefully branch on.
   *
   * The backend rate-limits this to 3 per minute per IP. The UI applies its own
   * cooldown on top so the common double-click does not spend the budget and
   * leave the user staring at a 429.
   */
  async resendVerificationEmail(): Promise<void> {
    const res = await this.authFetch(`${API_BASE_URL}/auth/resend-verification`, {
      method: 'POST',
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new ApiError(res.status, body);
    }
  }

  /**
   * Changes the password. On success the backend revokes every other session
   * and mints a brand-new one for this device — the returned token must
   * replace whatever `AuthContext` is holding, or this tab keeps working off
   * an access token whose refresh cookie was just invalidated.
   */
  async changePassword(currentPassword: string, newPassword: string): Promise<SessionResult> {
    const res = await this.authFetch(`${API_BASE_URL}/users/me/password`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ currentPassword, newPassword }),
    });
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new ApiError(res.status, body);
    }
    return res.json();
  }

  // -- Organizations ---------------------------------------------------
  //
  // No localStorage fallback here either, and for a sharper reason than the
  // account endpoints above: this is the authorization surface. Fabricating a
  // membership locally when the network is down would let the UI show an
  // "admin" a Members panel and an approve button that quietly do nothing —
  // the one place where an optimistic lie is worse than an error message.
  //
  // `DemoOrgProvider` in `OrgContext.tsx` handles the offline/demo case
  // separately and visibly, so the mock-data mode still works without any of
  // these calls pretending to have succeeded.

  private async orgJson<T>(path: string, init: RequestInit = {}): Promise<T> {
    const res = await this.authFetch(`${API_BASE_URL}${path}`, init);
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new ApiError(res.status, body);
    }
    return res.json();
  }

  /** Everything the signed-in user belongs to, including pending and invited rows. */
  async getOrganizations(): Promise<Organization[]> {
    return this.orgJson<Organization[]>('/organizations');
  }

  /**
   * Creates an organization; the caller becomes its first administrator.
   *
   * `creationToken` is the raw value from an admin-issued creation link —
   * organization creation stopped being self-service, and the server rejects
   * the request without one. `JSON.stringify` drops `slug` entirely when it
   * is `undefined`, matching the backend's "derive from name" default.
   */
  async createOrganization(name: string, slug: string | undefined, creationToken: string): Promise<Organization> {
    return this.orgJson<Organization>('/organizations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, slug, creationToken }),
    });
  }

  /**
   * Checks whether a creation-link token is currently valid, without
   * spending a use.
   *
   * Purely advisory, same as the backend endpoint it calls: a token valid
   * here can still be exhausted, revoked, or expired by the time
   * `createOrganization` actually redeems it. This exists only so the
   * onboarding screen can say "this link is invalid" before the user fills
   * in a name and handle.
   */
  async validateCreationToken(token: string): Promise<boolean> {
    const result = await this.orgJson<{ valid: boolean }>(
      `/organizations/creation-tokens/validate?token=${encodeURIComponent(token)}`
    );
    return result.valid;
  }

  /**
   * Asks to join by handle. Returns the resulting membership, which is
   * `PENDING` unless the user had already been invited — in which case this is
   * the acceptance and comes back `ACTIVE`.
   */
  async requestToJoinOrganization(slug: string): Promise<Organization> {
    return this.orgJson<Organization>('/organizations/join-requests', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug }),
    });
  }

  /**
   * The roster, including the admin's pending-approval queue.
   *
   * `orgId` is passed explicitly rather than relying on the active
   * organization, because these calls act on a specific organization in the
   * URL and the backend rejects a header that disagrees with the path.
   */
  async getOrganizationMembers(orgId: string): Promise<OrgMember[]> {
    return this.orgJson<OrgMember[]>(`/organizations/${orgId}/members`, {
      headers: { [ORG_HEADER]: orgId },
    });
  }

  async approveMember(orgId: string, userId: string): Promise<void> {
    await this.orgJson(`/organizations/${orgId}/members/${userId}/approval`, {
      method: 'POST',
      headers: { [ORG_HEADER]: orgId },
    });
  }

  async inviteMember(orgId: string, email: string, role: OrgRole = 'ORG_USER'): Promise<void> {
    await this.orgJson(`/organizations/${orgId}/members/invitations`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [ORG_HEADER]: orgId },
      body: JSON.stringify({ email, role }),
    });
  }

  async changeMemberRole(orgId: string, userId: string, role: OrgRole): Promise<void> {
    await this.orgJson(`/organizations/${orgId}/members/${userId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', [ORG_HEADER]: orgId },
      body: JSON.stringify({ role }),
    });
  }

  /**
   * Removes a member. Their access ends on their next request — the backend
   * re-reads membership every time rather than trusting the access token — so
   * there is nothing for the UI to do about their existing session.
   */
  async removeMember(orgId: string, userId: string): Promise<void> {
    await this.orgJson(`/organizations/${orgId}/members/${userId}`, {
      method: 'DELETE',
      headers: { [ORG_HEADER]: orgId },
    });
  }

  // -- Admin panel -------------------------------------------------------
  //
  // Global, cross-organization views for a SUPER_ADMIN. No localStorage
  // fallback here either, for the same reason as the organization endpoints
  // above — this is authorization surface, and there is no honest offline
  // stand-in for "every user in the system". `orgJson` is reused as the
  // request helper even though nothing here is organization-scoped; it is
  // just "authenticated fetch that throws ApiError on failure", which is
  // exactly what these calls need too.

  async getAdminUsers(): Promise<AdminUser[]> {
    return this.orgJson<AdminUser[]>('/admin/users');
  }

  /**
   * Suspends or lifts a suspension. Suspending also revokes every
   * refresh-token family server-side, so the account cannot mint a new
   * access token — there is nothing further for the client to do about an
   * existing session.
   */
  async setUserSuspended(userId: string, suspended: boolean): Promise<void> {
    await this.orgJson(`/admin/users/${userId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ suspended }),
    });
  }

  async getAdminOrganizations(): Promise<AdminOrganization[]> {
    return this.orgJson<AdminOrganization[]>('/admin/organizations');
  }

  async getCreationLinks(): Promise<OrganizationCreationLink[]> {
    return this.orgJson<OrganizationCreationLink[]>('/admin/organization-creation-tokens');
  }

  /**
   * Issues a new organization-creation link. The raw token in the result is
   * shown exactly once — the server stores only its hash and cannot recover
   * it afterward, the same guarantee password-reset links have.
   */
  async createCreationLink(
    label: string | undefined,
    maxUses: number,
    expiresInHours: number
  ): Promise<CreateCreationLinkResult> {
    return this.orgJson<CreateCreationLinkResult>('/admin/organization-creation-tokens', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ label, maxUses, expiresInHours }),
    });
  }

  async revokeCreationLink(id: string): Promise<void> {
    await this.orgJson(`/admin/organization-creation-tokens/${id}`, { method: 'DELETE' });
  }
}

export const api = new ApiService();
