/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const BACKEND = 'http://localhost:8080';

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
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: BACKEND },
      '/oauth2': { target: BACKEND, changeOrigin: true },
      '/login/oauth2': { target: BACKEND, changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
  },
});
