export interface Attachment {
  id: string;
  visitId: string;
  fileUrl: string;
  fileType: string;
  fileSize: number;
  caption?: string;
  tag: 'BEFORE' | 'AFTER' | 'PROCEDURE' | 'DOCUMENT';
  uploadedAt: string;
}

export interface Visit {
  id: string;
  clientId: string;
  visitDateTime: string;
  durationMinutes: number;
  procedureNotes: string;
  status: 'COMPLETED' | 'SCHEDULED' | 'CANCELLED';
  attachments: Attachment[];
  createdAt: string;
}

export interface Client {
  id: string;
  name: string;
  phone: string;
  email?: string;
  tags: string[];
  customFields: Record<string, string | number | boolean>;
  totalVisits: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateClientInput {
  name: string;
  phone: string;
  email?: string;
  tags: string[];
  customFields: Record<string, string | number | boolean>;
}

/** A user's system-wide privilege. Mirrors `auth/Roles.GlobalRole` on the backend. */
export type GlobalRole = 'USER' | 'SUPER_ADMIN';

/** The signed-in user's own profile — distinct from `Client`, which is a salon customer record. */
export interface UserProfile {
  id: string;
  email: string;
  fullName: string;
  createdAt: string;
  /**
   * Defaults to `'USER'` when absent rather than being made optional and left
   * undefined: code that checks `user.globalRole === 'SUPER_ADMIN'` to decide
   * whether to render the admin panel entry point must fail closed on a
   * response that forgot the field, not silently show nothing being wrong.
   */
  globalRole?: GlobalRole;
}

/** A user's capability within one organization. Mirrors `auth/Roles.OrgRole` on the backend. */
export type OrgRole = 'ORG_ADMIN' | 'ORG_USER';

/**
 * Where a membership sits in the join handshake. Only `ACTIVE` grants access to
 * any data — `SUSPENDED` is an admin's deliberate block, the other two are
 * merely recorded intent.
 */
export type MembershipStatus = 'ACTIVE' | 'PENDING' | 'INVITED' | 'SUSPENDED';

/**
 * An organization, together with *this* user's standing in it.
 *
 * Role and status travel with the organization because they are only meaningful
 * as a pair: the same salon is `ORG_ADMIN/ACTIVE` to its owner and
 * `ORG_USER/PENDING` to someone who has just asked to join.
 */
export interface Organization {
  id: string;
  name: string;
  slug: string;
  role: OrgRole;
  status: MembershipStatus;
  createdAt?: string;
}

/** A member of an organization, as listed to an administrator. */
export interface OrgMember {
  userId: string;
  email: string;
  fullName: string;
  role: OrgRole;
  status: MembershipStatus;
  joinedAt: string;
}

// ---------------------------------------------------------------------------
// Admin panel — global views for a SUPER_ADMIN. Distinct from the types above,
// which are always scoped to one organization or one user's own membership.
// ---------------------------------------------------------------------------

/** One account, as listed in the admin panel's global user table. */
export interface AdminUser {
  id: string;
  email: string;
  fullName: string;
  globalRole: GlobalRole;
  emailVerified: boolean;
  /** Null when the account is in good standing. */
  suspendedAt: string | null;
  organizationCount: number;
  createdAt: string;
}

/** One organization, as listed in the admin panel's global organization table. */
export interface AdminOrganization {
  id: string;
  name: string;
  slug: string;
  createdByEmail: string | null;
  memberCount: number;
  createdAt: string;
}

/**
 * An organization-creation link's metadata, as listed to the admin who can
 * manage it. Never carries the raw token — see [CreateCreationTokenResult].
 */
export interface OrganizationCreationLink {
  id: string;
  label: string | null;
  createdByEmail: string;
  maxUses: number;
  usesCount: number;
  expiresAt: string;
  revokedAt: string | null;
  createdAt: string;
}

/** The one-time response to issuing a link — the only place the raw token ever appears. */
export interface CreateCreationLinkResult {
  token: string;
  info: OrganizationCreationLink;
}

export interface CreateVisitInput {
  clientId: string;
  visitDateTime: string;
  durationMinutes: number;
  procedureNotes: string;
  status: 'COMPLETED' | 'SCHEDULED' | 'CANCELLED';
}
