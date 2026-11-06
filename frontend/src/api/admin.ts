import { apiClient } from './client';
import type { CouponEvent } from './coupons';

/** POST /api/admin/coupons 바디 (백엔드 CouponCreateRequest 와 같은 모양, 시각은 ISO-8601 UTC) */
export interface CreateEventInput {
  name: string;
  /** 선택 (최대 500자). 비우면 null */
  description: string | null;
  totalQuantity: number;
  issueStartAt: string;
  issueEndAt: string;
}

/** GET /api/admin/coupons/{id}/reconciliation — Redis 재고 ↔ DB 발급 대조 (읽기 전용) */
export interface StockReconciliation {
  couponId: number;
  totalQuantity: number;
  eventEnded: boolean;
  /** false 면 Redis 에 재고 키가 없다 (적재 실패) */
  redisLoaded: boolean;
  redisRemaining: number | null;
  redisIssuedCount: number;
  dbIssuedCount: number;
  dbIssuedQuantity: number;
  problems: string[];
  consistent: boolean;
}

export async function createEvent(input: CreateEventInput): Promise<CouponEvent> {
  const response = await apiClient.post<CouponEvent>('/api/admin/coupons', input);
  return response.data;
}

export async function fetchStockReconciliation(couponId: number): Promise<StockReconciliation> {
  const response = await apiClient.get<StockReconciliation>(`/api/admin/coupons/${couponId}/reconciliation`);
  return response.data;
}
