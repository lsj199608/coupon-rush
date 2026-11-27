package com.couponrush.coupon.reconcile;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 종료된 이벤트의 Redis ↔ DB 대조를 주기적으로 실행한다. 불일치는 WARN 로그로만 남긴다.
 * app.coupon.reconcile.enabled=false 로 끌 수 있다 (테스트에서는 끈다).
 * <p>
 * 서버가 여러 대면 ShedLock 으로 한 대만 실행한다(8-5) — 읽기 전용이라 두 번 돌아도 데이터는 안전하지만,
 * 사람이 봐야 하는 WARN 이 서버 수만큼 찍히고 조회도 그만큼 늘어서.
 * lock-at-least-for(9분)는 주기(10분)보다 조금 짧게: 작업이 금방 끝나도 표시가 남아 있어 늦게 깨어난 다른 서버가 또 돌리지 않고,
 * 다음 주기에는 풀려 있다. 관리자 API(GET .../reconciliation)는 이 잠금과 무관하다.
 */
@Component
@ConditionalOnProperty(name = "app.coupon.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class CouponStockReconcileScheduler {

    static final String LOCK_NAME = "coupon-stock-reconcile";

    private final CouponStockReconciler reconciler;

    public CouponStockReconcileScheduler(CouponStockReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(initialDelayString = "${app.coupon.reconcile.initial-delay:PT1M}",
            fixedDelayString = "${app.coupon.reconcile.interval:PT10M}")
    @SchedulerLock(name = LOCK_NAME,
            lockAtLeastFor = "${app.coupon.reconcile.lock-at-least-for:PT9M}",
            lockAtMostFor = "${app.coupon.reconcile.lock-at-most-for:PT9M}")
    public void reconcileEndedEvents() {
        reconciler.reconcileRecentlyEnded();
    }
}
