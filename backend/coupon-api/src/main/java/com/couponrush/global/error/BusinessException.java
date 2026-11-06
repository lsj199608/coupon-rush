package com.couponrush.global.error;

/** 예상 가능한 실패(인증 실패, 리소스 없음 등). GlobalExceptionHandler 가 ErrorCode 의 상태코드로 응답한다. */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
