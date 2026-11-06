package com.couponrush.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 마감(deadline_at)이 지난 PENDING 을 FAILED(TIMEOUT) 로 정리한다.
 * 조회하면 그 자리에서도 확정되지만(LlmRequestService.get), 화면을 닫아 아무도 조회하지 않는 요청도 끝내야 하므로 따로 돈다.
 * llm-service 가 죽어 있거나 결과 메시지를 잃어도 요청이 영원히 PENDING 으로 남지 않게 하는 장치.
 */
@Component
public class LlmRequestTimeouts {

    private static final Logger log = LoggerFactory.getLogger(LlmRequestTimeouts.class);

    private final LlmRequestRepository repository;
    private final Clock clock;

    public LlmRequestTimeouts(LlmRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** @return TIMEOUT 으로 바꾼 건수 */
    public int sweep() {
        int count = repository.timeoutAllExpired(clock.instant());
        if (count > 0) {
            log.warn("LLM 요청 {}건 TIMEOUT 처리 (llm-service 가 떠 있는지, 처리가 밀리는지 확인)", count);
        }
        return count;
    }
}
