// @vitest-environment node
// (vite.config 를 불러오면 esbuild 도 로드되는데, esbuild 는 jsdom 환경에서 동작하지 않는다 — 설정만 읽으므로 node 로 충분)
import { describe, expect, it } from 'vitest';
import viteConfig from '../vite.config';
import { ROUTES } from './routes';

/**
 * 회귀 방지: Vite 프록시 규칙(경로 앞부분 일치)이 프론트 화면 경로를 가로채면,
 * 그 화면을 새로고침하거나 주소를 직접 입력했을 때 React 대신 백엔드 응답(예: 401 JSON)이 나온다.
 * (실제로 '/login' 규칙 때문에 로그인 화면 새로고침 시 {"code":"UNAUTHORIZED"} 가 보였다)
 */
describe('Vite 프록시 규칙과 화면 경로', () => {
  const proxyPrefixes = Object.keys(viteConfig.server?.proxy ?? {});

  // Vite 는 문자열 키를 "경로가 이 문자열로 시작하면" 프록시한다
  const intercepted = (path: string) => proxyPrefixes.filter((prefix) => path.startsWith(prefix));

  it.each(Object.entries(ROUTES))('화면 %s (%s) 는 어떤 프록시 규칙에도 걸리지 않는다', (_name, path) => {
    expect(intercepted(path)).toEqual([]);
    expect(intercepted(`${path}?error=login_failed`)).toEqual([]);   // 쿼리가 붙어도 마찬가지
  });

  it('백엔드로 가야 하는 경로는 프록시된다', () => {
    expect(intercepted('/api/users/me')).toEqual(['/api']);
    expect(intercepted('/oauth2/authorization/kakao')).toEqual(['/oauth2']);
    expect(intercepted('/login/oauth2/code/kakao')).toEqual(['/login/oauth2']);
  });
});
