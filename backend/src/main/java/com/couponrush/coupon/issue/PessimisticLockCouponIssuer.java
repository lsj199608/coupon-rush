package com.couponrush.coupon.issue;

import com.couponrush.coupon.Coupon;
import com.couponrush.coupon.CouponIssue;
import com.couponrush.coupon.CouponRepository;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * [비관적 락] SELECT ... FOR UPDATE → 자바에서 확인 → 메모리 +1 → 커밋.
 * 3단계 순진한 구현과 로직은 같고, "읽는 순간 행을 잠근다"는 것만 다르다.
 * 락: 조회 순간 ~ 커밋 (조건부 UPDATE 보다 일찍 잡고 오래 쥔다). 대기 중인 요청은 DB 커넥션을 붙잡고 기다린다.
 */
@Component
public class PessimisticLockCouponIssuer implements CouponIssuer {

    private final CouponRepository couponRepository;
    private final CouponIssueSupport support;
    private final Clock clock;

    public PessimisticLockCouponIssuer(CouponRepository couponRepository, CouponIssueSupport support, Clock clock) {
        this.couponRepository = couponRepository;
        this.support = support;
        this.clock = clock;
    }

    @Override
    public IssueStrategy strategy() {
        return IssueStrategy.PESSIMISTIC_LOCK;
    }

    @Override
    @Transactional
    public CouponIssue issue(Long couponId, Long userId) {
        Instant now = clock.instant();
        support.ensureNotIssued(couponId, userId);

        Coupon coupon = couponRepository.findByIdForUpdate(couponId)       // ← 여기서 줄을 선다
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
        support.validateIssuable(coupon, now);                             // 락 안에서 읽은 값이라 최신
        coupon.increaseIssuedQuantity();                                   // 커밋 때 UPDATE (락 보유 중)

        return support.saveIssue(couponId, userId, now);
    }
}
