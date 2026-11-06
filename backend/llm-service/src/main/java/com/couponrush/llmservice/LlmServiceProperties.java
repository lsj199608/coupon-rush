package com.couponrush.llmservice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * llm-service 설정 (app.llm).
 *
 * @param resultRetention 처리한 요청의 결과를 Redis 에 보관하는 기간 — 같은 메시지가 다시 와도 LLM 을 다시 부르지 않고 결과만 다시 보낸다
 * @param publishTimeout  llm-results 발행 ack 대기
 * @param fake            fake 제공자 설정
 */
@ConfigurationProperties("app.llm")
public record LlmServiceProperties(
        @DefaultValue("1d") Duration resultRetention,
        @DefaultValue("10s") Duration publishTimeout,
        @DefaultValue Fake fake) {

    /** @param delay 응답 전 대기 (로컬에서 타임아웃·폴링 화면을 눈으로 확인할 때만. 기본 0) */
    public record Fake(@DefaultValue("0ms") Duration delay) {
    }
}
