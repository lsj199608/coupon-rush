/** TanStack Query 키를 한 곳에서 관리한다 (무효화할 때 오타로 캐시가 안 지워지는 일 방지) */
export const queryKeys = {
  events: ['events'] as const,
  event: (id: number) => ['events', id] as const,
  issueRequest: (requestId: string) => ['issue-requests', requestId] as const,
  myCoupons: ['my-coupons'] as const,
  stockCheck: (couponId: number) => ['admin', 'stock-check', couponId] as const,
};
