package com.couponrush.llm;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link LlmRequestTimeouts} 주기 실행 (기본 30초). 테스트에서는 꺼 두고 sweep() 을 직접 부른다.
 * <p>
 * 서버가 여러 대면 ShedLock 으로 한 대만 실행한다(8-5). UPDATE 가 status = 'PENDING' 조건부라 두 번 돌아도 한쪽만 바꾸지만,
 * 헛도는 쿼리와 같은 WARN 을 없앤다. lock-at-least-for(25초)는 주기(30초)보다 조금 짧게 — 이유는 CouponStockReconcileScheduler 와 같다.
 */
@Component
@ConditionalOnProperty(name = "app.llm.timeout-sweep.enabled", havingValue = "true", matchIfMissing = true)
public class LlmRequestTimeoutScheduler {

    static final String LOCK_NAME = "llm-request-timeout-sweep";

    private final LlmRequestTimeouts timeouts;

    public LlmRequestTimeoutScheduler(LlmRequestTimeouts timeouts) {
        this.timeouts = timeouts;
    }

    @Scheduled(initialDelayString = "${app.llm.timeout-sweep.initial-delay:${app.llm.timeout-sweep.interval:PT30S}}",
            fixedDelayString = "${app.llm.timeout-sweep.interval:PT30S}")
    @SchedulerLock(name = LOCK_NAME,
            lockAtLeastFor = "${app.llm.timeout-sweep.lock-at-least-for:PT25S}",
            lockAtMostFor = "${app.llm.timeout-sweep.lock-at-most-for:PT25S}")
    public void sweep() {
        timeouts.sweep();
    }
}
