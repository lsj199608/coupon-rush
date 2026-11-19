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
        return with(anthropic, chat("http://localhost:1/mcp", "test-service-token"));
    }

    public static LlmServiceProperties with(LlmServiceProperties.Anthropic anthropic, LlmServiceProperties.Chat chat) {
        return new LlmServiceProperties("fake", Duration.ofDays(1), Duration.ofSeconds(10),
                new LlmServiceProperties.Fake(Duration.ZERO), anthropic, breaker(),
                new LlmServiceProperties.Budget(50_000), new LlmServiceProperties.Cache(true, Duration.ofHours(24)), chat);
    }

    /** 챗봇 기본값 (도구 5회, 토큰 20,000, 마감 여유 5초) + 도구 서버 주소·토큰 */
    public static LlmServiceProperties.Chat chat(String mcpUrl, String serviceToken) {
        return new LlmServiceProperties.Chat(2, 5, 20_000, 8000, Duration.ofSeconds(5),
                new LlmServiceProperties.Chat.Mcp(mcpUrl, serviceToken, Duration.ofSeconds(5)));
    }
}
