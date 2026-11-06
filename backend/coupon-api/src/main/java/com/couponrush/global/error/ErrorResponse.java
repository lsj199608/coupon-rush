package com.couponrush.global.error;

/** 모든 API 에러 응답의 공통 형태: {"code": "TOKEN_EXPIRED", "message": "..."} */
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage());
    }
}
