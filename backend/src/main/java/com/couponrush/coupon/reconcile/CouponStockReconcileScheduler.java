package com.couponrush.coupon.reconcile;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 종료된 이벤트의 Redis ↔ DB 대조를 주기적으로 실행한다. 불일치는 WARN 로그로만 남긴다.
 * app.coupon.reconcile.enabled=false 로 끌 수 있다 (테스트에서는 끈다).
 * ⚠ 서버를 여러 대 띄우면 서버마다 실행된다 — 읽기 전용이라 해는 없지만, 운영에서는 ShedLock 등으로 한 대만 돌게 한다.
 */
@Component
@ConditionalOnProperty(name = "app.coupon.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class CouponStockReconcileScheduler {

    private final CouponStockReconciler reconciler;

    public CouponStockReconcileScheduler(CouponStockReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(initialDelayString = "${app.coupon.reconcile.initial-delay:PT1M}",
            fixedDelayString = "${app.coupon.reconcile.interval:PT10M}")
    public void reconcileEndedEvents() {
        reconciler.reconcileRecentlyEnded();
    }
}
