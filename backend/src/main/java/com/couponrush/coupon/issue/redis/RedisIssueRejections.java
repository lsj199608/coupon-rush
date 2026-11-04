package com.couponrush.coupon.issue.redis;

import com.couponrush.coupon.CouponRepository;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Lua 판정 결과(SUCCESS 제외)를 API 에러로 바꾼다. 동기(RedisCouponIssuer)·비동기(AsyncCouponIssueRequester)가 함께 쓴다.
 * NOT_READY 일 때만 DB 를 조회해서 이유를 구분한다 (실패 경로에서만).
 */
@Component
public class RedisIssueRejections {

    private final CouponRepository couponRepository;

    public RedisIssueRejections(CouponRepository couponRepository) {
        this.couponRepository = couponRepository;
    }

    public BusinessException toException(RedisIssueResult result, Long couponId, Instant now) {
        return switch (result) {
            case DUPLICATE -> new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
            case SOLD_OUT -> new BusinessException(ErrorCode.COUPON_SOLD_OUT);
            case NOT_IN_PERIOD -> new BusinessException(ErrorCode.COUPON_NOT_IN_PERIOD);
            case NOT_READY -> couponRepository.findById(couponId)
                    .map(coupon -> coupon.isInIssuePeriod(now)
                            ? new BusinessException(ErrorCode.COUPON_STOCK_NOT_READY)
                            : new BusinessException(ErrorCode.COUPON_NOT_IN_PERIOD))
                    .orElseGet(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
            case SUCCESS -> throw new IllegalArgumentException("SUCCESS 는 거절 사유가 아닙니다.");
        };
    }
}
