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

export interface CreateVisitInput {
  clientId: string;
  visitDateTime: string;
  durationMinutes: number;
  procedureNotes: string;
  status: 'COMPLETED' | 'SCHEDULED' | 'CANCELLED';
}
