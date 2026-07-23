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

export interface CreateVisitInput {
  clientId: string;
  visitDateTime: string;
  durationMinutes: number;
  procedureNotes: string;
  status: 'COMPLETED' | 'SCHEDULED' | 'CANCELLED';
}
