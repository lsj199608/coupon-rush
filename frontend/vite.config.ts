/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const BACKEND = 'http://localhost:8080';
/** 채팅 서버 web-chat-service (7단계 D) */
const WEB_CHAT = 'http://localhost:8083';

/**
 * 개발 서버(5173)가 /api, /oauth2, /login 요청을 백엔드(8080)로 넘긴다.
 * → 브라우저 입장에서는 모든 요청이 같은 출처(localhost:5173)라 CORS 설정이 필요 없다.
 *
 * ⚠ 프록시 규칙은 "경로 앞부분 일치"다. 프론트 화면 경로(/login 등)와 겹치면 새로고침할 때 그 화면 대신 백엔드 응답이 나온다.
 *   그래서 백엔드의 /login 경로 중 실제로 쓰는 OAuth 콜백(/login/oauth2/code/*)만 넘긴다. ('/login' 전체를 넘기면
 *   로그인 화면을 새로고침했을 때 백엔드의 401 JSON 이 보였다) — src/routes.test.ts 가 겹침을 검사한다.
 *
 * /oauth2, /login/oauth2 는 changeOrigin: true — 백엔드가 받는 Host 를 localhost:8080 으로 바꾼다.
 *   Spring 은 Host 로 OAuth redirect_uri({baseUrl}/login/oauth2/code/{provider}) 를 만들기 때문에,
 *   이렇게 해야 각 콘솔에 등록해 둔 8080 콜백 주소와 일치한다. (5173 으로 만들면 "등록되지 않은 redirect_uri" 오류)
 *   카카오 등은 로그인 후 브라우저를 8080 콜백으로 보내고, 백엔드가 refresh 쿠키를 심은 뒤 5173/oauth/callback 으로 돌려보낸다.
 *   쿠키는 포트를 구분하지 않으므로(localhost) 이후 5173 → /api/auth/refresh 요청에도 실린다.
 *
 * ⚠ 배포(8단계 Kubernetes)에서는 같은 규칙을 Nginx 게이트웨이(frontend/nginx/default.conf)가 한다 — 경로 규칙을 바꾸면 두 곳을 함께 고친다.
 *
 * 채팅(7단계 D4)은 두 서비스에 나뉜다 — 연결 티켓 /api/web-chat/tickets 는 coupon-api(8080), 지난 대화 /api/web-chat/rooms 와
 *   WebSocket /ws/web-chat 은 web-chat-service(8083).
 *   ⚠ Vite 는 규칙을 **적은 순서대로** 보고 처음 맞는 것을 쓴다 → 더 긴 /api/web-chat/rooms 를 /api 보다 먼저 둔다 (routes.test.ts 가 검사).
 *   /ws/web-chat 은 ws: true (WebSocket 업그레이드도 넘김). changeOrigin 은 끈다 — 채팅 서버가 Origin(5173)을 허용 목록과 비교하는데,
 *   프록시는 Origin 을 그대로 넘기므로 브라우저가 보낸 값이 검사된다.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api/web-chat/rooms': { target: WEB_CHAT },
      '/ws/web-chat': { target: WEB_CHAT, ws: true },
      '/api': { target: BACKEND },
      '/oauth2': { target: BACKEND, changeOrigin: true },
      '/login/oauth2': { target: BACKEND, changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // vitest 4부터 restoreMocks 는 vi.spyOn 만 되돌린다 → vi.fn 의 호출 기록·구현도 테스트마다 되돌리려면 mockReset 이 함께 필요 (v3 의 restoreMocks 와 같은 동작)
    restoreMocks: true,
    mockReset: true,
  },
});
