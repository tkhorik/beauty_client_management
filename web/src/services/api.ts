import type { Client, Visit, Attachment, CreateClientInput, CreateVisitInput } from '../types';

const API_BASE_URL = 'http://localhost:8080/api';

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
    return JSON.parse(saved);
  }

  private saveLocalVisits(visits: Visit[]) {
    localStorage.setItem('beauty_visits', JSON.stringify(visits));
  }

  async getClients(query?: string, tagFilter?: string): Promise<Client[]> {
    try {
      const url = new URL(`${API_BASE_URL}/clients`);
      if (query) url.searchParams.set('q', query);
      if (tagFilter) url.searchParams.set('tag', tagFilter);
      const res = await fetch(url.toString());
      if (res.ok) return await res.json();
    } catch (err) {
      // Backend not running -> fallback to LocalStorage
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
      const res = await fetch(`${API_BASE_URL}/clients/${id}`);
      if (res.ok) return await res.json();
    } catch (err) {}
    const clients = this.getLocalClients();
    return clients.find(c => c.id === id) || null;
  }

  async createClient(input: CreateClientInput): Promise<Client> {
    try {
      const res = await fetch(`${API_BASE_URL}/clients`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input)
      });
      if (res.ok) return await res.json();
    } catch (err) {}

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
      const res = await fetch(`${API_BASE_URL}/clients/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input)
      });
      if (res.ok) return await res.json();
    } catch (err) {}

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
      await fetch(`${API_BASE_URL}/clients/${id}`, { method: 'DELETE' });
    } catch (err) {}

    const clients = this.getLocalClients().filter(c => c.id !== id);
    const visits = this.getLocalVisits().filter(v => v.clientId !== id);
    this.saveLocalClients(clients);
    this.saveLocalVisits(visits);
  }

  async getVisits(clientId?: string): Promise<Visit[]> {
    try {
      const url = new URL(`${API_BASE_URL}/visits`);
      if (clientId) url.searchParams.set('clientId', clientId);
      const res = await fetch(url.toString());
      if (res.ok) return await res.json();
    } catch (err) {}

    let visits = this.getLocalVisits();
    if (clientId) {
      visits = visits.filter(v => v.clientId === clientId);
    }
    return visits.sort((a, b) => new Date(b.visitDateTime).getTime() - new Date(a.visitDateTime).getTime());
  }

  async createVisit(input: CreateVisitInput): Promise<Visit> {
    try {
      const res = await fetch(`${API_BASE_URL}/visits`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input)
      });
      if (res.ok) return await res.json();
    } catch (err) {}

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

      const res = await fetch(`${API_BASE_URL}/attachments/upload`, {
        method: 'POST',
        body: formData
      });
      if (res.ok) return await res.json();
    } catch (err) {}

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
}

export const api = new ApiService();
