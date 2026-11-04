package com.couponrush.coupon.issue;

import com.couponrush.coupon.Coupon;
import com.couponrush.coupon.CouponIssue;
import com.couponrush.coupon.CouponIssueRepository;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 세 방식이 공통으로 쓰는 검증/저장 로직. 동시성 제어와 무관한 부분을 한 곳에 모아 방식 간 차이만 드러나게 한다 */
@Component
public class CouponIssueSupport {

    private static final String DUPLICATE_ISSUE_CONSTRAINT = "uk_coupon_issues_coupon_user";

    private final CouponIssueRepository couponIssueRepository;

    public CouponIssueSupport(CouponIssueRepository couponIssueRepository) {
        this.couponIssueRepository = couponIssueRepository;
    }

    /** 빠른 실패: 이미 받은 사람은 재고 락/차감까지 가지 않게 한다 (동시 요청의 최종 판정은 유니크 제약) */
    public void ensureNotIssued(Long couponId, Long userId) {
        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
        }
    }

    /** 엔티티로 읽어서 판정하는 방식(비관적/낙관적)용 */
    public void validateIssuable(Coupon coupon, Instant now) {
        if (!coupon.isInIssuePeriod(now)) {
            throw new BusinessException(ErrorCode.COUPON_NOT_IN_PERIOD);
        }
        if (coupon.isSoldOut()) {
            throw new BusinessException(ErrorCode.COUPON_SOLD_OUT);
        }
    }

    /**
     * 발급 이력 INSERT. 같은 사용자의 동시 요청이 ensureNotIssued 를 함께 통과한 경우
     * 유니크 제약 위반을 COUPON_ALREADY_ISSUED 로 바꾼다 → 예외로 트랜잭션이 롤백되며 재고 차감도 취소된다.
     */
    public CouponIssue saveIssue(Long couponId, Long userId, Instant now) {
        try {
            return couponIssueRepository.saveAndFlush(new CouponIssue(couponId, userId, now));
        } catch (DataIntegrityViolationException e) {
            String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
            if (message != null && message.contains(DUPLICATE_ISSUE_CONSTRAINT)) {
                throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
            }
            throw e;
        }
    }
}
