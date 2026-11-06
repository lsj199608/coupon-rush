import { apiClient } from './client';

/** 백엔드에서 "쿠폰" 한 행 = 화면의 "이벤트" 하나 (API 경로는 /api/coupons) */
export type EventStatus = 'UPCOMING' | 'OPEN' | 'ENDED';

export interface CouponEvent {
  id: number;
  name: string;
  /** 이벤트 설명 (없으면 null) */
  description: string | null;
  totalQuantity: number;
  issuedQuantity: number;
  /** Redis 재고 기준 (발급 판정의 기준이라 가장 정확) */
  remainingQuantity: number;
  issueStartAt: string;
  issueEndAt: string;
  /** 서버 시각 기준 상태 */
  status: EventStatus;
  /** 응답 시점의 서버 시각 — 카운트다운을 서버 시계에 맞추는 데 쓴다 */
  serverTime: string;
}

export type IssueRequestStatus = 'PENDING' | 'COMPLETED' | 'FAILED';

export interface IssueRequestResult {
  requestId: string;
  couponId: number;
  status: IssueRequestStatus;
  couponIssueId?: number;
  failureReason?: string;
  requestedAt: string;
  processedAt?: string;
}

export interface MyCoupon {
  issueId: number;
  couponId: number;
  couponName: string;
  issuedAt: string;
}

/** 발급 요청 결과: 비동기(기본)는 202 + requestId, 동기 모드는 201 + 발급 결과 */
export type IssueStart =
  | { kind: 'accepted'; requestId: string }
  | { kind: 'issued'; issueId: number };

export async function fetchEvents(): Promise<CouponEvent[]> {
  const response = await apiClient.get<CouponEvent[]>('/api/coupons');
  return response.data;
}

export async function fetchEvent(id: number): Promise<CouponEvent> {
  const response = await apiClient.get<CouponEvent>(`/api/coupons/${id}`);
  return response.data;
}

export async function requestIssue(id: number): Promise<IssueStart> {
  const response = await apiClient.post<{ requestId?: string; issueId?: number }>(`/api/coupons/${id}/issue`);
  if (response.status === 202 && response.data.requestId) {
    return { kind: 'accepted', requestId: response.data.requestId };
  }
  return { kind: 'issued', issueId: response.data.issueId ?? 0 };
}

export async function fetchIssueRequest(requestId: string): Promise<IssueRequestResult> {
  const response = await apiClient.get<IssueRequestResult>(`/api/issue-requests/${requestId}`);
  return response.data;
}

export async function fetchMyCoupons(): Promise<MyCoupon[]> {
  const response = await apiClient.get<MyCoupon[]>('/api/users/me/coupons');
  return response.data;
}
