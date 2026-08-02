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

/** The signed-in user's own profile — distinct from `Client`, which is a salon customer record. */
export interface UserProfile {
  id: string;
  email: string;
  fullName: string;
  createdAt: string;
}

/** A user's capability within one organization. Mirrors `auth/Roles.OrgRole` on the backend. */
export type OrgRole = 'ORG_ADMIN' | 'ORG_USER';

/**
 * Where a membership sits in the join handshake. Only `ACTIVE` grants access to
 * any data — the other two are recorded intent and nothing more.
 */
export type MembershipStatus = 'ACTIVE' | 'PENDING' | 'INVITED';

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

export interface CreateVisitInput {
  clientId: string;
  visitDateTime: string;
  durationMinutes: number;
  procedureNotes: string;
  status: 'COMPLETED' | 'SCHEDULED' | 'CANCELLED';
}
