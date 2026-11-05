import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import * as authApi from '../api/auth';
import { tokenStore } from '../api/tokenStore';

type AuthStatus = 'loading' | 'authenticated' | 'anonymous';

interface AuthContextValue {
  status: AuthStatus;
  user: authApi.Me | undefined;
  /** 소셜 로그인 시작 — 로그인 후 돌아올 경로를 기억해 두고 백엔드로 페이지 이동 */
  startLogin: (provider: authApi.OAuthProvider, returnTo?: string) => void;
  /** 콜백 페이지에서 호출 — refresh 쿠키로 access token 을 받아 로그인 상태로 전환 */
  completeLogin: () => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export const RETURN_TO_KEY = 'coupon-rush:return-to';
export const ME_QUERY_KEY = ['me'] as const;

/**
 * 로그인 상태 관리.
 * - 앱 시작 시 refresh 쿠키로 세션 복원을 한 번 시도한다 (새로고침하면 메모리의 access token 이 사라지므로)
 * - 사용자 정보는 GET /api/users/me 를 TanStack Query 로 캐시한다 (토큰이 있을 때만 호출)
 * - axios 인터셉터가 재발급에도 실패하면(onSessionExpired) 로그아웃 상태로 바꾼다
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [hasToken, setHasToken] = useState<boolean>(() => tokenStore.get() !== null);
  const [bootstrapped, setBootstrapped] = useState(false);

  const meQuery = useQuery({
    queryKey: ME_QUERY_KEY,
    queryFn: authApi.fetchMe,
    enabled: hasToken,
    retry: false,
    staleTime: 5 * 60 * 1000,
  });

  // 앱 시작: 세션 복원 (실패하면 비로그인 — 정상 상황)
  useEffect(() => {
    let cancelled = false;
    authApi
      .restoreSession()
      .then(() => !cancelled && setHasToken(true))
      .catch(() => !cancelled && setHasToken(false))
      .finally(() => !cancelled && setBootstrapped(true));
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(
    () =>
      tokenStore.onSessionExpired(() => {
        setHasToken(false);
        queryClient.removeQueries({ queryKey: ME_QUERY_KEY });
      }),
    [queryClient],
  );

  const startLogin = useCallback((provider: authApi.OAuthProvider, returnTo?: string) => {
    if (returnTo) {
      sessionStorage.setItem(RETURN_TO_KEY, returnTo);
    }
    window.location.assign(authApi.oauthLoginUrl(provider));
  }, []);

  const completeLogin = useCallback(async () => {
    await authApi.restoreSession();
    setHasToken(true);
    setBootstrapped(true);
    await queryClient.invalidateQueries({ queryKey: ME_QUERY_KEY });
  }, [queryClient]);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      setHasToken(false);
      queryClient.clear();
    }
  }, [queryClient]);

  const status: AuthStatus = useMemo(() => {
    if (!bootstrapped || (hasToken && meQuery.isPending)) return 'loading';
    if (hasToken && meQuery.data) return 'authenticated';
    return 'anonymous';
  }, [bootstrapped, hasToken, meQuery.isPending, meQuery.data]);

  const value = useMemo<AuthContextValue>(
    () => ({ status, user: meQuery.data, startLogin, completeLogin, logout }),
    [status, meQuery.data, startLogin, completeLogin, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth 는 AuthProvider 안에서만 쓸 수 있습니다.');
  }
  return context;
}
