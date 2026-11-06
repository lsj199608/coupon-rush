package com.couponrush.coupon.issue;

import com.couponrush.coupon.CouponIssue;
import com.couponrush.coupon.CouponRepository;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * [조건부 UPDATE] 확인 + 차감을 DB 한 문장으로.
 * 락: UPDATE 가 잡는 암묵적 행 락 (UPDATE 순간 ~ 커밋). 자바에서 값을 읽고 판단하지 않으므로 대기 후에도 재평가된다.
 */
@Component
public class ConditionalUpdateCouponIssuer implements CouponIssuer {

    private final CouponRepository couponRepository;
    private final CouponIssueSupport support;
    private final Clock clock;

    public ConditionalUpdateCouponIssuer(CouponRepository couponRepository, CouponIssueSupport support, Clock clock) {
        this.couponRepository = couponRepository;
        this.support = support;
        this.clock = clock;
    }

    @Override
    public IssueStrategy strategy() {
        return IssueStrategy.CONDITIONAL_UPDATE;
    }

    @Override
    @Transactional
    public CouponIssue issue(Long couponId, Long userId) {
        Instant now = clock.instant();
        support.ensureNotIssued(couponId, userId);

        if (couponRepository.increaseIssuedQuantity(couponId, now) == 0) {
            throw failureReason(couponId, now);
        }
        return support.saveIssue(couponId, userId, now);
    }

    /** 0 행 갱신의 이유를 구분한다 (실패 경로에서만 추가 조회) */
    private BusinessException failureReason(Long couponId, Instant now) {
        return couponRepository.findById(couponId)
                .map(coupon -> coupon.isInIssuePeriod(now)
                        ? new BusinessException(ErrorCode.COUPON_SOLD_OUT)
                        : new BusinessException(ErrorCode.COUPON_NOT_IN_PERIOD))
                .orElseGet(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
    }
}
