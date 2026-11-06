package com.couponrush.global.error;

import org.springframework.http.HttpStatus;

/**
 * API 에러 코드. 프론트엔드는 message 가 아니라 code 로 분기한다
 * (예: TOKEN_EXPIRED → /api/auth/refresh 호출 후 재시도, 그 외 401 → 다시 로그인).
 */
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),

    // 인증
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "로그아웃된 토큰입니다."),
    REFRESH_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 없습니다."),
    REFRESH_TOKEN_MISMATCH(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 일치하지 않습니다. 다시 로그인해 주세요."),

    // 회원
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),

    // 쿠폰
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다."),
    COUPON_NOT_IN_PERIOD(HttpStatus.BAD_REQUEST, "쿠폰 발급 기간이 아닙니다."),
    COUPON_SOLD_OUT(HttpStatus.CONFLICT, "쿠폰이 모두 소진되었습니다."),
    COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 발급받은 쿠폰입니다."),
    /** 낙관적 락 방식에서 재시도 횟수를 넘긴 경우 (재고는 남아 있을 수 있음) */
    COUPON_ISSUE_CONFLICT(HttpStatus.CONFLICT, "요청이 몰려 발급하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    /** Redis 방식에서 재고가 적재되지 않은 경우 (관리자 재동기화 필요) */
    COUPON_STOCK_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "쿠폰 발급 준비 중입니다. 잠시 후 다시 시도해 주세요."),
    /** 비동기 모드에서 Kafka 발행이 확정되지 않은 경우 (Redis 는 되돌림) */
    COUPON_ISSUE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "발급 요청을 접수하지 못했습니다. 잠시 후 다시 시도해 주세요."),

    // 발급 요청 (비동기)
    ISSUE_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "발급 요청을 찾을 수 없습니다."),

    // LLM 요청 (7단계)
    LLM_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "문구 생성 요청을 찾을 수 없습니다."),
    /** 사용자별 분당 한도 초과 (기본 5회) — 잠시 후 다시 */
    LLM_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 잦습니다. 1분 뒤에 다시 시도해 주세요."),
    /** 사용자별 일일 한도 초과 (기본 50회) — 내일 다시 */
    LLM_DAILY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "오늘 문구 생성 횟수를 모두 사용했습니다. 내일 다시 시도해 주세요."),
    /** llm-requests 발행이 확정되지 않음 (요청은 FAILED(PUBLISH_FAILED) 로 남는다) */
    LLM_REQUEST_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "문구 생성 요청을 접수하지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
