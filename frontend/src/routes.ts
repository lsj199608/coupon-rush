/**
 * 프론트 화면 경로. 새로고침·주소 직접 입력 시 브라우저가 이 경로로 "페이지"를 요청한다.
 * ⚠ vite.config.ts 의 프록시 규칙(경로 앞부분 일치)이 이 경로를 가로채면 백엔드 응답(JSON)이 화면에 나온다.
 *   새 화면을 추가하면 여기에 넣는다 — routes.test.ts 가 프록시와 겹치는지 검사한다.
 *   (관리자 화면은 /admin — 백엔드 관리자 API 는 /api/admin 이라 겹치지 않는다)
 */
export const ROUTES = {
  home: '/',
  eventDetail: '/events/:id',
  myCoupons: '/me/coupons',
  adminEventNew: '/admin/events/new',
  login: '/login',
  oauthCallback: '/oauth/callback',
} as const;

export function eventPath(id: number): string {
  return ROUTES.eventDetail.replace(':id', String(id));
}
