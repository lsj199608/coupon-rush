package com.couponrush.coupon.issue.redis;

import com.couponrush.coupon.CouponIssue;
import com.couponrush.coupon.CouponRepository;
import com.couponrush.coupon.issue.CouponIssueSupport;
import com.couponrush.coupon.issue.CouponIssuer;
import com.couponrush.coupon.issue.IssueStrategy;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * [Redis] 재고 판정을 Redis Lua 스크립트로 하고, SUCCESS 일 때만 DB 에 저장한다.
 *
 *  1) Lua: 기간 → 중복 → 재고 확인 → DECR + SADD  (원자적, DB 접근 없음)
 *  2) SUCCESS 일 때만 DB 트랜잭션: 발급 이력 INSERT + issued_quantity +1
 *  3) DB 가 실패하면 Redis 를 되돌린다 (보상)
 *
 * 소진·중복·기간 외 요청은 DB 를 전혀 거치지 않는다 → DB 에 오는 쓰기는 "재고 수"만큼으로 줄어든다.
 * DB 트랜잭션은 Redis 호출이 끝난 뒤에 짧게 연다 (Redis 를 기다리는 동안 DB 커넥션을 쥐고 있지 않게).
 */
@Component
public class RedisCouponIssuer implements CouponIssuer {

    private static final Logger log = LoggerFactory.getLogger(RedisCouponIssuer.class);

    private final RedisCouponStock stock;
    private final CouponRepository couponRepository;
    private final CouponIssueSupport support;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public RedisCouponIssuer(RedisCouponStock stock, CouponRepository couponRepository, CouponIssueSupport support,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.stock = stock;
        this.couponRepository = couponRepository;
        this.support = support;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public IssueStrategy strategy() {
        return IssueStrategy.REDIS;
    }

    @Override
    public CouponIssue issue(Long couponId, Long userId) {
        Instant now = clock.instant();

        switch (stock.tryIssue(couponId, userId, now)) {
            case SUCCESS -> {
                return persistOrCompensate(couponId, userId, now);
            }
            case DUPLICATE -> throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
            case SOLD_OUT -> throw new BusinessException(ErrorCode.COUPON_SOLD_OUT);
            case NOT_IN_PERIOD -> throw new BusinessException(ErrorCode.COUPON_NOT_IN_PERIOD);
            case NOT_READY -> throw notReadyReason(couponId, now);
        }
        throw new IllegalStateException("알 수 없는 Redis 발급 결과");
    }

    private CouponIssue persistOrCompensate(Long couponId, Long userId, Instant now) {
        try {
            return transactionTemplate.execute(status -> {
                CouponIssue issue = support.saveIssue(couponId, userId, now);   // INSERT 먼저 → 쿠폰 행 락은 마지막에 짧게
                if (couponRepository.increaseIssuedQuantity(couponId, now) == 0) {
                    // Redis 는 재고가 있다고 했는데 DB 는 아니라고 함 → 두 저장소가 어긋난 상태
                    throw new IllegalStateException("Redis 와 DB 의 재고가 일치하지 않습니다. couponId=" + couponId);
                }
                return issue;
            });
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.COUPON_ALREADY_ISSUED) {
                // DB 에는 이미 발급 이력이 있음 (Redis Set 이 유실됐다가 재적재 전 등) → 재고만 되돌리고 Set 에는 남긴다
                compensate(couponId, userId, false);
            } else {
                compensate(couponId, userId, true);
            }
            throw e;
        } catch (RuntimeException e) {
            compensate(couponId, userId, true);
            throw e;
        }
    }

    private void compensate(Long couponId, Long userId, boolean removeUser) {
        try {
            stock.restore(couponId, userId, removeUser);
        } catch (RuntimeException e) {
            // 보상까지 실패하면 Redis 재고가 실제보다 1 적어진다 (초과 발급이 아니라 덜 발급되는 쪽 → 안전한 방향)
            // POST /api/admin/coupons/{id}/stock/sync 로 DB 기준 재동기화한다
            log.error("Redis 재고 보상 실패: couponId={}, userId={} — 재동기화 필요", couponId, userId, e);
        }
    }

    /** 재고가 적재되지 않은 경우: DB 로 이유를 구분한다 (실패 경로에서만 DB 조회) */
    private BusinessException notReadyReason(Long couponId, Instant now) {
        return couponRepository.findById(couponId)
                .map(coupon -> coupon.isInIssuePeriod(now)
                        ? new BusinessException(ErrorCode.COUPON_STOCK_NOT_READY)
                        : new BusinessException(ErrorCode.COUPON_NOT_IN_PERIOD))
                .orElseGet(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
    }
}
