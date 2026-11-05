import { AxiosError } from 'axios';
import { describe, expect, it } from 'vitest';
import { apiError } from '../test/renderApp';
import { errorCodeOf, errorMessageOf, failureReasonMessage, isFinalRejection } from './errors';
import { formatCountdown } from '../utils/format';

describe('에러 코드 → 메시지', () => {
  it('백엔드 코드를 꺼내 코드별 메시지로 바꾼다', () => {
    expect(errorCodeOf(apiError(409, 'COUPON_SOLD_OUT'))).toBe('COUPON_SOLD_OUT');
    expect(errorMessageOf(apiError(409, 'COUPON_SOLD_OUT'))).toBe('아쉽게도 쿠폰이 모두 소진되었어요.');
    expect(errorMessageOf(apiError(409, 'COUPON_ALREADY_ISSUED'))).toBe('이미 발급받은 쿠폰이에요.');
  });

  it('모르는 코드나 일반 에러는 공통 문구', () => {
    expect(errorMessageOf(apiError(500, 'SOMETHING_NEW'))).toContain('문제가 생겼어요');
    expect(errorMessageOf(new Error('boom'))).toContain('문제가 생겼어요');
  });

  it('응답이 아예 없으면(네트워크) 연결 안내', () => {
    expect(errorMessageOf(new AxiosError('Network Error'))).toContain('서버에 연결할 수 없어요');
  });

  it('컨슈머 실패 사유별 메시지', () => {
    expect(failureReasonMessage('COUPON_ALREADY_ISSUED')).toBe('이미 발급받은 쿠폰이에요.');
    expect(failureReasonMessage('PUBLISH_CANCELLED')).toContain('접수가 취소');
    expect(failureReasonMessage('RETRY_EXHAUSTED')).toContain('발급 처리 중 문제');
    expect(failureReasonMessage(undefined)).toContain('문제가 생겼어요');
  });

  it('다시 눌러도 결과가 같은 거절만 최종으로 본다', () => {
    expect(isFinalRejection('COUPON_SOLD_OUT')).toBe(true);
    expect(isFinalRejection('COUPON_ALREADY_ISSUED')).toBe(true);
    expect(isFinalRejection('COUPON_NOT_IN_PERIOD')).toBe(true);
    expect(isFinalRejection('COUPON_ISSUE_UNAVAILABLE')).toBe(false);
    expect(isFinalRejection(undefined)).toBe(false);
  });
});

describe('카운트다운 표시', () => {
  it('시:분:초, 하루 이상이면 일 포함, 지나면 0', () => {
    expect(formatCountdown(90_000)).toBe('00:01:30');
    expect(formatCountdown(26 * 3_600_000 + 61_000)).toBe('1일 02:01:01');
    expect(formatCountdown(-5_000)).toBe('00:00:00');
  });
});
