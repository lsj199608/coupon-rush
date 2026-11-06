package com.couponrush.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** {@link LlmRequestTimeouts} 주기 실행 (기본 30초). 테스트에서는 꺼 두고 sweep() 을 직접 부른다 */
@Component
@ConditionalOnProperty(name = "app.llm.timeout-sweep.enabled", havingValue = "true", matchIfMissing = true)
public class LlmRequestTimeoutScheduler {

    private final LlmRequestTimeouts timeouts;

    public LlmRequestTimeoutScheduler(LlmRequestTimeouts timeouts) {
        this.timeouts = timeouts;
    }

    @Scheduled(initialDelayString = "${app.llm.timeout-sweep.interval:PT30S}",
            fixedDelayString = "${app.llm.timeout-sweep.interval:PT30S}")
    public void sweep() {
        timeouts.sweep();
    }
}
