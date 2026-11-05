import { isAxiosError } from 'axios';
import type { ApiError } from './client';

/**
 * 백엔드 에러 코드 → 사용자 메시지. 화면에서 메시지를 직접 만들지 않고 여기서만 정한다.
 * (코드는 백엔드 global/error/ErrorCode, 컨슈머 FAILED 사유와 이름이 같다)
 */
const MESSAGES: Record<string, string> = {
  // 발급 요청 거절 (Redis 판정 단계, 즉시 응답)
  COUPON_SOLD_OUT: '아쉽게도 쿠폰이 모두 소진되었어요.',
  COUPON_ALREADY_ISSUED: '이미 발급받은 쿠폰이에요.',
  COUPON_NOT_IN_PERIOD: '지금은 발급 기간이 아니에요.',
  COUPON_NOT_FOUND: '이벤트를 찾을 수 없어요.',
  COUPON_STOCK_NOT_READY: '발급 준비 중이에요. 잠시 후 다시 시도해 주세요.',
  COUPON_ISSUE_UNAVAILABLE: '요청이 몰려 접수하지 못했어요. 잠시 후 다시 시도해 주세요.',
  COUPON_ISSUE_CONFLICT: '요청이 몰려 발급하지 못했어요. 잠시 후 다시 시도해 주세요.',
  ISSUE_REQUEST_NOT_FOUND: '발급 결과를 확인할 수 없어요. 내 쿠폰에서 확인해 주세요.',
  // 인증
  UNAUTHORIZED: '로그인이 필요해요.',
  TOKEN_EXPIRED: '로그인이 만료되었어요. 다시 로그인해 주세요.',
  TOKEN_INVALID: '로그인이 만료되었어요. 다시 로그인해 주세요.',
  TOKEN_REVOKED: '로그아웃된 상태예요. 다시 로그인해 주세요.',
  FORBIDDEN: '권한이 없어요.',
  INVALID_INPUT: '요청 값이 올바르지 않아요.',
};

/** 컨슈머가 DB 저장에 실패한 사유(FAILED.failureReason) → 메시지 */
const FAILURE_REASON_MESSAGES: Record<string, string> = {
  COUPON_ALREADY_ISSUED: MESSAGES.COUPON_ALREADY_ISSUED,
  PUBLISH_CANCELLED: '접수가 취소되었어요. 다시 시도해 주세요.',
  RETRY_EXHAUSTED: '발급 처리 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.',
  DATA_INTEGRITY_VIOLATION: '발급 처리 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.',
  STOCK_MISMATCH: '발급 처리 중 문제가 생겼어요. 잠시 후 다시 시도해 주세요.',
};

const GENERIC = '문제가 생겼어요. 잠시 후 다시 시도해 주세요.';
const NETWORK = '서버에 연결할 수 없어요. 네트워크를 확인해 주세요.';

/** 다시 눌러도 결과가 바뀌지 않는 거절 — 재시도 버튼을 보여주지 않는다 */
const FINAL_CODES = new Set(['COUPON_SOLD_OUT', 'COUPON_ALREADY_ISSUED', 'COUPON_NOT_IN_PERIOD', 'COUPON_NOT_FOUND']);

export function errorCodeOf(error: unknown): string | undefined {
  if (isAxiosError<ApiError>(error)) {
    return error.response?.data?.code;
  }
  return undefined;
}

export function errorMessageOf(error: unknown): string {
  if (isAxiosError(error) && !error.response) {
    return NETWORK;
  }
  const code = errorCodeOf(error);
  return (code && MESSAGES[code]) || GENERIC;
}

/**
 * 입력 검증 실패(400 INVALID_INPUT)는 서버가 어떤 필드가 왜 틀렸는지 message 에 담아 준다 → 그대로 보여 준다.
 * (관리자 폼처럼 사용자가 값을 고쳐야 하는 화면용. 그 밖의 코드는 errorMessageOf 와 같다)
 */
export function formErrorMessageOf(error: unknown): string {
  if (isAxiosError<ApiError>(error) && error.response?.data?.code === 'INVALID_INPUT' && error.response.data.message) {
    return `입력값을 확인해 주세요. (${error.response.data.message})`;
  }
  return errorMessageOf(error);
}

export function failureReasonMessage(reason: string | undefined): string {
  return (reason && FAILURE_REASON_MESSAGES[reason]) || GENERIC;
}

export function isFinalRejection(code: string | undefined): boolean {
  return code !== undefined && FINAL_CODES.has(code);
}
