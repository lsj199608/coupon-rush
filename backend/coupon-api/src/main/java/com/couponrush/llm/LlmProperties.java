package com.couponrush.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;

/**
 * coupon-api 쪽 LLM 요청 설정 (app.llm). LLM 키·모델·토큰 예산은 llm-service 설정이라 여기 없다.
 *
 * @param requestTimeout 접수부터 이 시간 안에 결과가 없으면 FAILED(TIMEOUT). 메시지의 deadline 이 된다
 * @param publishTimeout llm-requests 발행 시 브로커 ack 를 기다리는 최대 시간 (넘으면 503)
 * @param zone           "하루"·"1분" 창을 자르는 시간대 (일일 한도가 한국 자정에 초기화되도록)
 * @param rateLimit      사용자별 요청 한도
 * @param timeoutSweep   마감 지난 PENDING 을 TIMEOUT 으로 정리하는 스케줄러
 * @param dailyTokenBudget 일일 토큰 예산 (LLM_DAILY_TOKEN_BUDGET — llm-service 와 같은 값). 이미 넘었으면 접수 단계에서 거절
 */
@ConfigurationProperties("app.llm")
public record LlmProperties(
        @DefaultValue("60s") Duration requestTimeout,
        @DefaultValue("5s") Duration publishTimeout,
        @DefaultValue("Asia/Seoul") ZoneId zone,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue TimeoutSweep timeoutSweep,
        @DefaultValue("50000") long dailyTokenBudget) {

    /** 고정 창(fixed window) 카운터. 분 경계에서 순간적으로 최대 2배가 될 수 있지만, 비용 상한 목적에는 충분하다 */
    public record RateLimit(@DefaultValue("5") int perMinute, @DefaultValue("50") int perDay) {
    }

    public record TimeoutSweep(@DefaultValue("true") boolean enabled, @DefaultValue("30s") Duration interval) {
    }
}
