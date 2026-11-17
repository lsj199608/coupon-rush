import type { EventStatus } from '../api/coupons';

/**
 * 이벤트 상태 → 화면 문구. 뱃지(components/StatusBadge)와 검색 조건 칩(utils/searchFilter)이 함께 쓴다.
 * 컴포넌트 파일에 두지 않는 이유: utils 가 components 를 import 하는 역방향 의존이 생기고,
 * 컴포넌트가 아닌 값을 export 하는 .tsx 는 Vite Fast Refresh 가 컴포넌트 단위로 갱신하지 못한다.
 */
export const STATUS_LABELS: Record<EventStatus, string> = {
  UPCOMING: '오픈 전',
  OPEN: '진행 중',
  ENDED: '종료',
};
