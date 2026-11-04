package com.couponrush.coupon.issue.async;

/** 형식이 깨진 메시지(poison pill). 다시 읽어도 깨져 있으므로 재시도하지 않고 바로 DLT 로 보낸다 */
public class InvalidCouponIssueMessageException extends RuntimeException {

    public InvalidCouponIssueMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
