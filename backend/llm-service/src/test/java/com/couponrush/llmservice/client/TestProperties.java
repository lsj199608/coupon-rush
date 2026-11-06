package com.couponrush.llmservice.client;

import com.couponrush.llmservice.LlmServiceProperties;

import java.time.Duration;

/** 단위 테스트용 설정 (실제 API 주소는 쓰지 않는다 — baseUrl 은 WireMock) */
public final class TestProperties {

    private TestProperties() {
    }

    public static LlmServiceProperties.Anthropic anthropic(String baseUrl) {
        return new LlmServiceProperties.Anthropic("test-key", baseUrl, "claude-sonnet-5-5", "low", 4000,
                Duration.ofSeconds(2), 1, true);
    }

    public static LlmServiceProperties.CircuitBreaker breaker() {
        return new LlmServiceProperties.CircuitBreaker(50, 4, 4, Duration.ofSeconds(30));
    }

    public static LlmServiceProperties withAnthropic(LlmServiceProperties.Anthropic anthropic) {
        return new LlmServiceProperties("fake", Duration.ofDays(1), Duration.ofSeconds(10),
                new LlmServiceProperties.Fake(Duration.ZERO), anthropic, breaker(),
                new LlmServiceProperties.Budget(50_000), new LlmServiceProperties.Cache(true, Duration.ofHours(24)));
    }
}
