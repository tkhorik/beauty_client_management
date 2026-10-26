import { createContext } from 'react';
import type { UserProfile } from '../types';

export interface AuthContextValue {
  token: string | null;
  user: UserProfile | null;
  initialising: boolean;
  sessionGeneration: number;
  login: (token: string, user: UserProfile) => void;
  logout: () => void;
  updateUser: (user: UserProfile) => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);
