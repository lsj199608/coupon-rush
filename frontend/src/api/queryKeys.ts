/** TanStack Query 키를 한 곳에서 관리한다 (무효화할 때 오타로 캐시가 안 지워지는 일 방지) */
export const queryKeys = {
  events: ['events'] as const,
  event: (id: number) => ['events', id] as const,
  issueRequest: (requestId: string) => ['issue-requests', requestId] as const,
  myCoupons: ['my-coupons'] as const,
  /**
   * 검색 접수(질문별) — 같은 질문으로 돌아오면 다시 접수하지 않는다.
   * 두 번째 자리를 'query'로 고정: 사용자 입력이 그 자리에 오면 질문 "request"가 아래 폴링 키의 앞부분과 같아져
   * 앞부분 일치(invalidate·remove 등)로 다루면 서로 섞인다.
   */
  eventSearch: (query: string) => ['event-search', 'query', query] as const,
  eventSearchRequest: (requestId: string) => ['event-search', 'request', requestId] as const,
  stockCheck: (couponId: number) => ['admin', 'stock-check', couponId] as const,
  llmRequest: (requestId: string) => ['admin', 'llm-requests', requestId] as const,
  chatSessions: ['chat', 'sessions'] as const,
  chatSession: (sessionId: number) => ['chat', 'sessions', sessionId] as const,
};
