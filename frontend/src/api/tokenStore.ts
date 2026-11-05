/**
 * Access Token 은 메모리(이 모듈의 변수)에만 둔다.
 * - localStorage / sessionStorage 에 두면 XSS 로 페이지에 들어온 스크립트가 그대로 읽어 갈 수 있다.
 * - 메모리는 새로고침하면 사라진다 → 앱 시작 시 HttpOnly refresh 쿠키로 다시 받아 온다 (AuthContext).
 * Refresh Token 은 HttpOnly 쿠키라 프론트 코드가 손댈 수 없다(읽을 수도, 지울 수도 없음).
 */
let accessToken: string | null = null;

/** 재발급까지 실패해서 세션이 끝났을 때 알릴 곳 (AuthContext 가 구독해서 로그인 화면으로 보낸다) */
type SessionExpiredListener = () => void;
const sessionExpiredListeners = new Set<SessionExpiredListener>();

export const tokenStore = {
  get: () => accessToken,
  set: (token: string | null) => {
    accessToken = token;
  },
  clear: () => {
    accessToken = null;
  },
  onSessionExpired(listener: SessionExpiredListener) {
    sessionExpiredListeners.add(listener);
    return () => {
      sessionExpiredListeners.delete(listener);
    };
  },
  notifySessionExpired() {
    sessionExpiredListeners.forEach((listener) => listener());
  },
};
