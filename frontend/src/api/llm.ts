import { apiClient } from './client';

/**
 * 관리자 LLM 문구 생성 (coupon-api /api/admin/llm/**). 화면은 llm-service 를 직접 부르지 않는다 —
 * coupon-api 가 202 로 접수하고, 결과는 상태 조회를 폴링한다 (발급 흐름과 같은 모양).
 */
export type EventCopyTone = 'FRIENDLY' | 'URGENT' | 'PREMIUM' | 'PLAYFUL';

export const TONE_LABELS: Record<EventCopyTone, string> = {
  FRIENDLY: '친근하게',
  URGENT: '긴박하게',
  PREMIUM: '고급스럽게',
  PLAYFUL: '재미있게',
};

/** POST /api/admin/llm/event-copy 바디 (백엔드 EventCopyRequest) — 기간은 비워도 된다 */
export interface EventCopyInput {
  keywords: string;
  tone: EventCopyTone;
  totalQuantity: number;
  issueStartAt?: string;
  issueEndAt?: string;
  /** true 면 같은 입력의 저장된 문구 대신 새로 생성 */
  regenerate: boolean;
}

export interface EventCopyCandidate {
  name: string;
  description: string;
}

export type LlmRequestStatus = 'PENDING' | 'COMPLETED' | 'FAILED';

/** GET /api/admin/llm/requests/{id} */
export interface LlmRequestResult {
  requestId: string;
  type: 'EVENT_COPY';
  status: LlmRequestStatus;
  candidates: EventCopyCandidate[];
  failureReason: string | null;
  /** FAILED 일 때 [다시 시도]를 보여 줄지 */
  retryable: boolean | null;
  model: string | null;
  inputTokens: number;
  outputTokens: number;
  /** 같은 입력의 이전 결과를 재사용했는가 */
  cached: boolean;
  createdAt: string;
  deadlineAt: string;
  completedAt: string | null;
}

export interface LlmRequestAccepted {
  requestId: string;
  status: 'PENDING';
  deadlineAt: string;
}

/** 키워드 길이 상한 (백엔드 EventCopyInput.KEYWORDS_MAX) */
export const KEYWORDS_MAX = 200;

export async function requestEventCopy(input: EventCopyInput): Promise<LlmRequestAccepted> {
  const response = await apiClient.post<LlmRequestAccepted>('/api/admin/llm/event-copy', input);
  return response.data;
}

export async function fetchLlmRequest(requestId: string): Promise<LlmRequestResult> {
  const response = await apiClient.get<LlmRequestResult>(`/api/admin/llm/requests/${requestId}`);
  return response.data;
}
