/** TanStack Query 키를 한 곳에서 관리한다 (무효화할 때 오타로 캐시가 안 지워지는 일 방지) */
export const queryKeys = {
  events: ['events'] as const,
  event: (id: number) => ['events', id] as const,
  issueRequest: (requestId: string) => ['issue-requests', requestId] as const,
  myCoupons: ['my-coupons'] as const,
  /** 검색 접수(질문별) — 같은 질문으로 돌아오면 다시 접수하지 않는다 */
  eventSearch: (query: string) => ['event-search', query] as const,
  eventSearchRequest: (requestId: string) => ['event-search', 'request', requestId] as const,
  stockCheck: (couponId: number) => ['admin', 'stock-check', couponId] as const,
  llmRequest: (requestId: string) => ['admin', 'llm-requests', requestId] as const,
};
