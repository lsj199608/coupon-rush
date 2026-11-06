package com.couponrush.coupon.issue;

import com.couponrush.coupon.Coupon;
import com.couponrush.coupon.CouponIssue;
import com.couponrush.coupon.CouponRepository;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * [낙관적 락] 락 없이 읽고 → 메모리 +1 → UPDATE ... WHERE id = ? AND version = ?
 * 다른 트랜잭션이 먼저 바꿨으면 0행 → 충돌 예외 → 롤백 후 처음부터 재시도.
 *
 * - 재시도마다 새 트랜잭션이 필요해 @Transactional 대신 TransactionTemplate 으로 시도 단위 트랜잭션을 연다.
 *   (같은 클래스 안의 @Transactional 메서드 호출은 프록시를 거치지 않아 트랜잭션이 안 열린다)
 * - 충돌이 드문 상황을 전제로 한 방식이다. 선착순처럼 모두가 같은 행을 노리면 매 라운드 1명만 성공하고 나머지는 재시도한다.
 */
@Component
public class OptimisticLockCouponIssuer implements CouponIssuer {

    private final CouponRepository couponRepository;
    private final CouponIssueSupport support;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int maxAttempts;

    /** 관측용: 지금까지 발생한 버전 충돌(=재시도) 횟수 */
    private final AtomicLong conflictCount = new AtomicLong();

    public OptimisticLockCouponIssuer(CouponRepository couponRepository, CouponIssueSupport support,
                                      PlatformTransactionManager transactionManager, Clock clock,
                                      CouponIssueProperties properties) {
        this.couponRepository = couponRepository;
        this.support = support;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.maxAttempts = properties.optimisticMaxAttempts();
    }

    @Override
    public IssueStrategy strategy() {
        return IssueStrategy.OPTIMISTIC_LOCK;
    }

    @Override
    public CouponIssue issue(Long couponId, Long userId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 바깥 트랜잭션에 합류하면 충돌 후 재시도가 같은 (이미 롤백 표시된) 트랜잭션에서 돌아 의미가 없다
            throw new IllegalStateException("낙관적 락 발급은 바깥 트랜잭션 없이 호출해야 합니다.");
        }

        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> attemptOnce(couponId, userId));
            } catch (OptimisticLockingFailureException e) {
                conflictCount.incrementAndGet();
                if (attempt >= maxAttempts) {
                    throw new BusinessException(ErrorCode.COUPON_ISSUE_CONFLICT);
                }
                backoff(attempt);
            }
        }
    }

    public long getConflictCount() {
        return conflictCount.get();
    }

    private CouponIssue attemptOnce(Long couponId, Long userId) {
        Instant now = clock.instant();
        support.ensureNotIssued(couponId, userId);

        Coupon coupon = couponRepository.findById(couponId)                 // 락 없이 읽음 (version 기억)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
        support.validateIssuable(coupon, now);
        coupon.increaseIssuedQuantity();
        couponRepository.flush();   // UPDATE ... WHERE version = ? → 0행이면 여기서 충돌 (발급 이력 INSERT 전에 빨리 실패)

        return support.saveIssue(couponId, userId, now);
    }

    /** 다 같이 즉시 재시도하면 또 동시에 충돌하므로, 시도 횟수에 비례한 범위에서 무작위로 쉰다 (jitter) */
    private static void backoff(int attempt) {
        long maxMillis = Math.min(50, 5L * attempt);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, maxMillis + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.COUPON_ISSUE_CONFLICT);
        }
    }
}
