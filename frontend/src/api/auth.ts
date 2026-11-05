import { apiClient, refreshAccessToken } from './client';
import { tokenStore } from './tokenStore';

export type OAuthProvider = 'kakao' | 'naver' | 'google';

export interface Me {
  id: number;
  provider: 'KAKAO' | 'NAVER' | 'GOOGLE';
  nickname: string;
  email: string | null;
  role: 'USER' | 'ADMIN';
}

/** 소셜 로그인 시작 주소. 페이지 이동(전체 리다이렉트)이라 axios 가 아니라 location 으로 간다 */
export function oauthLoginUrl(provider: OAuthProvider): string {
  return `/oauth2/authorization/${provider}`;
}

/** HttpOnly refresh 쿠키로 access token 을 받아 메모리에 둔다. 쿠키가 없거나 만료면 실패 */
export function restoreSession(): Promise<string> {
  return refreshAccessToken();
}

export async function fetchMe(): Promise<Me> {
  const response = await apiClient.get<Me>('/api/users/me');
  return response.data;
}

/**
 * 로그아웃: 서버가 refresh 를 지우고(쿠키 Max-Age=0) 지금 access token 을 블랙리스트에 넣는다.
 * 서버 호출이 실패해도(이미 만료 등) 메모리 토큰은 지운다 — 사용자가 로그아웃을 눌렀으면 화면은 로그아웃 상태여야 한다.
 */
export async function logout(): Promise<void> {
  try {
    await apiClient.post('/api/auth/logout');
  } finally {
    tokenStore.clear();
  }
}
