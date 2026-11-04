package com.couponrush.coupon.issue;

import com.couponrush.coupon.CouponIssue;

/**
 * 재고 차감 + 발급 이력 저장. 동시성 제어 방식만 구현체마다 다르고, 결과 규칙은 같다:
 * 성공 시 CouponIssue 반환 / 실패 시 BusinessException
 * (COUPON_NOT_FOUND, COUPON_NOT_IN_PERIOD, COUPON_SOLD_OUT, COUPON_ALREADY_ISSUED, COUPON_ISSUE_CONFLICT)
 *
 * 트랜잭션은 구현체가 직접 관리한다. 호출하는 쪽에서 트랜잭션으로 감싸지 않는다
 * (낙관적 락은 재시도마다 새 트랜잭션이 필요하다).
 */
public interface CouponIssuer {

    IssueStrategy strategy();

    CouponIssue issue(Long couponId, Long userId);
}
