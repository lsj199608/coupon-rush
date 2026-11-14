package com.couponrush.mcpserver.couponapi;

/**
 * coupon-api 호출 실패. 도구는 이것을 MCP 오류 결과(isError=true + 설명)로 바꾼다 — 예외로 올리면 클라이언트가 프로토콜 오류로 받아
 * 모델이 "무엇이 잘못됐는지" 읽을 수 없다.
 */
public class CouponApiException extends RuntimeException {

    public enum Kind {
        /** 없는 이벤트·검색 요청 (404) */
        NOT_FOUND,
        /** 입력이 잘못됨 (400) — 모델이 인자를 고쳐 다시 부를 수 있다 */
        INVALID_INPUT,
        /** coupon-api 장애·응답 없음·검색 지연 — 잠시 후 다시 */
        UNAVAILABLE
    }

    private final Kind kind;

    public CouponApiException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public CouponApiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
