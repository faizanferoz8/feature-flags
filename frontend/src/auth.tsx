import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, setSignedOutHandler, tokenStore } from './api';
import type { Role, Session } from './types';

interface Auth {
  session: Session | null;
  loading: boolean;
  signIn: (token: string, session: Session) => void;
  signOut: () => void;
  /** Whether the signed-in user's role includes the given one. */
  can: (role: Role) => boolean;
}

const RANK: Record<Role, number> = { VIEWER: 0, EDITOR: 1, ADMIN: 2 };
const AuthContext = createContext<Auth | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [hasToken, setHasToken] = useState(() => tokenStore.get() !== null);

  const signOut = useCallback(() => {
    tokenStore.clear();
    setHasToken(false);
    queryClient.clear();
  }, [queryClient]);

  useEffect(() => setSignedOutHandler(signOut), [signOut]);

  const sessionQuery = useQuery({
    queryKey: ['session'],
    queryFn: () => api<Session>('/api/session'),
    enabled: hasToken,
    retry: false,
  });

  const value = useMemo<Auth>(() => {
    const session = hasToken ? (sessionQuery.data ?? null) : null;
    return {
      session,
      loading: hasToken && sessionQuery.isPending,
      signIn: (token, fresh) => {
        tokenStore.set(token);
        queryClient.setQueryData(['session'], fresh);
        setHasToken(true);
      },
      signOut,
      can: (role) => session !== null && RANK[session.user.role] >= RANK[role],
    };
  }, [hasToken, sessionQuery.data, sessionQuery.isPending, queryClient, signOut]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): Auth {
  const auth = useContext(AuthContext);
  if (!auth) throw new Error('useAuth must be used inside AuthProvider');
  return auth;
}
