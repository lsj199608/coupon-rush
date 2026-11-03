package com.couponrush.coupon;

import com.couponrush.coupon.dto.MyCouponResponse;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 쿠폰 발급 (DB 기반).
 * 쿠폰 발급 핵심 경로에는 LLM 호출 등 외부 호출을 절대 넣지 않는다 (CLAUDE.md 규칙).
 *
 * 동시성 처리 — 두 가지 경쟁을 각각 DB 가 원자적으로 막는다:
 *  1) 재고: 조건부 UPDATE (issued < total 일 때만 +1)  → 초과 발급 방지
 *  2) 중복: (coupon_id, user_id) 유니크 제약            → 같은 사람 중복 발급 방지
 * 둘 중 하나라도 실패하면 예외 → 트랜잭션 롤백으로 재고 차감도 함께 취소된다.
 * 자세한 재현/분석: docs/concurrency-report.md
 */
@Service
public class CouponIssueService {

    private static final String DUPLICATE_ISSUE_CONSTRAINT = "uk_coupon_issues_coupon_user";

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final Clock clock;

    public CouponIssueService(CouponRepository couponRepository, CouponIssueRepository couponIssueRepository,
                              Clock clock) {
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
        this.clock = clock;
    }

    @Transactional
    public CouponIssue issue(Long couponId, Long userId) {
        Instant now = clock.instant();

        // 빠른 실패: 이미 받은 사람이 재고 행 락을 잡지 않게 한다 (최종 판정은 아래 유니크 제약)
        if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
            throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
        }

        // 확인 + 차감을 한 문장으로. 여기서 쿠폰 행에 락이 걸리고 커밋 때 풀린다
        if (couponRepository.increaseIssuedQuantity(couponId, now) == 0) {
            throw failureReason(couponId, now);
        }

        try {
            return couponIssueRepository.saveAndFlush(new CouponIssue(couponId, userId, now));
        } catch (DataIntegrityViolationException e) {
            // 동시에 들어온 같은 사용자의 요청이 위 exists 검사를 함께 통과한 경우
            if (isDuplicateIssue(e)) {
                throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);   // 롤백 → 차감도 취소
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<MyCouponResponse> getMyCoupons(Long userId) {
        return couponIssueRepository.findMyCoupons(userId);
    }

    /** 0 행 갱신의 이유를 구분해 알려준다 (실패 경로에서만 추가 조회) */
    private BusinessException failureReason(Long couponId, Instant now) {
        return couponRepository.findById(couponId)
                .map(coupon -> coupon.isInIssuePeriod(now)
                        ? new BusinessException(ErrorCode.COUPON_SOLD_OUT)
                        : new BusinessException(ErrorCode.COUPON_NOT_IN_PERIOD))
                .orElseGet(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
    }

    private static boolean isDuplicateIssue(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains(DUPLICATE_ISSUE_CONSTRAINT);
    }
}
