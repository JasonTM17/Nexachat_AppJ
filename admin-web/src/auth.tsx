import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { api, login as apiLogin, tokenStore, type MeResponse } from './api';

interface SessionState {
  me: MeResponse | null;
  loading: boolean;
  isAdmin: boolean;
  signIn: (identifier: string, password: string) => Promise<MeResponse>;
  signOut: () => void;
}

const SessionContext = createContext<SessionState>({
  me: null,
  loading: true,
  isAdmin: false,
  signIn: async () => {
    throw new Error('not ready');
  },
  signOut: () => {},
});

export function SessionProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<MeResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    if (!tokenStore.access) {
      setLoading(false);
      return;
    }
    api<MeResponse>('/api/v1/admin/me')
      .then((res) => {
        if (!cancelled) setMe(res);
      })
      .catch(() => {
        if (!cancelled) tokenStore.clear();
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const signIn = useCallback(async (identifier: string, password: string) => {
    await apiLogin(identifier, password);
    const res = await api<MeResponse>('/api/v1/admin/me');
    setMe(res);
    return res;
  }, []);

  const signOut = useCallback(() => {
    tokenStore.clear();
    setMe(null);
  }, []);

  const value = useMemo<SessionState>(
    () => ({
      me,
      loading,
      isAdmin: me?.role === 'ADMIN',
      signIn,
      signOut,
    }),
    [me, loading, signIn, signOut],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession() {
  return useContext(SessionContext);
}
