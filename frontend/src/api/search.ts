import { apiClient } from './client';
import type { CouponEvent, EventStatus } from './coupons';

/**
 * 자연어 이벤트 검색 (공개, coupon-api /api/coupons/search). AI 는 질문 → 검색 조건만 만들고 조회는 서버 DB 가 한다.
 * AI 를 쓸 수 없으면(한도·예산·장애·마감) 서버가 키워드 검색 결과를 FALLBACK 으로 준다 — 화면에서 따로 대체 검색을 하지 않는다.
 */
export type EventSearchState = 'PENDING' | 'COMPLETED' | 'FALLBACK';

/** 백엔드 SearchFallbackReason */
export type SearchFallbackReason = 'RATE_LIMITED' | 'BUDGET_EXCEEDED' | 'TIMEOUT' | 'LLM_UNAVAILABLE';

/** AI 가 만든 조건 (계약 EventSearchFilter). 기간은 [from, to) */
export interface EventSearchFilter {
  keywords: string[];
  statuses: EventStatus[];
  startFrom: string | null;
  startTo: string | null;
  endFrom: string | null;
  endTo: string | null;
  availableOnly: boolean;
}

export interface EventSearchResult {
  /** 접수부터 키워드 검색으로 끝났으면 null */
  requestId: string | null;
  status: EventSearchState;
  query: string;
  /** COMPLETED 일 때만 */
  filter: EventSearchFilter | null;
  /** FALLBACK 일 때만 */
  fallbackReason: SearchFallbackReason | null;
  /** FALLBACK: 질문에서 뽑은 단어 */
  keywords: string[];
  events: CouponEvent[];
  /** PENDING: 서버 마감 (지나면 서버가 키워드 검색으로 끝낸다) */
  deadlineAt: string | null;
}

/** 202 PENDING 또는 200 FALLBACK — 둘 다 같은 형식이라 상태 코드가 아니라 status 로 구분한다 */
export async function startEventSearch(query: string): Promise<EventSearchResult> {
  const response = await apiClient.post<EventSearchResult>('/api/coupons/search', { query });
  return response.data;
}

export async function fetchEventSearch(requestId: string): Promise<EventSearchResult> {
  const response = await apiClient.get<EventSearchResult>(`/api/coupons/search/${requestId}`);
  return response.data;
}
