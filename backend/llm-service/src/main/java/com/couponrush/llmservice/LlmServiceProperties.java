package com.couponrush.llmservice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * llm-service 설정 (app.llm).
 *
 * @param provider        fake(기본) | anthropic. anthropic 인데 API 키가 비어 있으면 fake 로 동작한다 (기동 실패 대신 경고 로그)
 * @param resultRetention 처리한 요청의 결과를 Redis 에 보관하는 기간 — 같은 메시지가 다시 와도 LLM 을 다시 부르지 않고 결과만 다시 보낸다
 * @param publishTimeout  llm-results 발행 ack 대기
 * @param fake            fake 제공자 설정
 * @param anthropic       Anthropic(Claude) 제공자 설정
 * @param circuitBreaker  제공자 장애 시 호출을 잠시 끊는 설정
 */
@ConfigurationProperties("app.llm")
public record LlmServiceProperties(
        @DefaultValue("fake") String provider,
        @DefaultValue("1d") Duration resultRetention,
        @DefaultValue("10s") Duration publishTimeout,
        @DefaultValue Fake fake,
        @DefaultValue Anthropic anthropic,
        @DefaultValue CircuitBreaker circuitBreaker,
        @DefaultValue Budget budget,
        @DefaultValue Cache cache) {

    /**
     * 일일 토큰 예산 (전체 서비스 합계, 한국 자정 초기화). 사용량은 Redis llm:usage:{yyyyMMdd} 에 기록한다.
     * 판정은 "호출 전 사용량 &lt; 예산"이라 동시에 처리 중인 요청(최대 consumer concurrency 개)만큼 살짝 넘을 수 있다 —
     * 정확히 막으려면 호출 전에 최대 토큰을 예약해야 하는데, 그러면 남은 예산이 실제보다 훨씬 적게 보여 정상 요청까지 거절된다.
     *
     * @param dailyTokens LLM_DAILY_TOKEN_BUDGET (기본 50,000)
     */
    public record Budget(@DefaultValue("50000") long dailyTokens) {
    }

    /**
     * 응답 캐시 — 같은 입력·같은 모델·같은 프롬프트면 LLM 을 부르지 않고 이전 결과를 쓴다 (비용 0).
     * "다시 생성"(regenerate=true)은 캐시를 읽지 않는다 (새 문구를 원하는 것이므로). 새 결과는 캐시를 덮어쓴다.
     */
    public record Cache(@DefaultValue("true") boolean enabled, @DefaultValue("24h") Duration ttl) {
    }

    /** @param delay 응답 전 대기 (로컬에서 타임아웃·폴링 화면을 눈으로 확인할 때만. 기본 0) */
    public record Fake(@DefaultValue("0ms") Duration delay) {
    }

    /**
     * @param apiKey     LLM_API_KEY. 비어 있으면 fake 로 동작
     * @param baseUrl    API 주소 (테스트에서 WireMock 으로 바꾼다)
     * @param model      모델 ID (LLM_MODEL, 기본 claude-sonnet-5-5)
     * @param effort     생각(thinking) 깊이 low|medium|high|xhigh|max — 짧은 문구라 low. 출력 토큰(=비용)에 직접 영향
     * @param maxTokens  한 번 호출의 출력 상한 (생각 + 후보 3개). 넘어서 잘리면 출력 검증 실패 → 1회 재생성
     * @param timeout    HTTP 요청 1회 타임아웃. SDK 재시도까지 합쳐 요청 마감(기본 60초) 안에 끝나게:
     *                   15초 × (1 + 재시도 2) ≈ 45초 + 백오프
     * @param maxRetries SDK 내장 재시도 (429, 5xx, 408/409, 연결 오류만 — 400 등은 재시도하지 않는다)
     * @param fallbacks  서버 측 거절 대비(fallbacks: "default", 베타 헤더). Claude API 전용 — 다른 플랫폼이면 끈다
     */
    public record Anthropic(
            @DefaultValue("") String apiKey,
            @DefaultValue("https://api.anthropic.com") String baseUrl,
            @DefaultValue("claude-sonnet-5-5") String model,
            @DefaultValue("low") String effort,
            @DefaultValue("4000") long maxTokens,
            @DefaultValue("15s") Duration timeout,
            @DefaultValue("2") int maxRetries,
            @DefaultValue("true") boolean fallbacks) {
    }

    /**
     * 최근 slidingWindowSize 번 호출 중 실패율이 failureRateThreshold% 이상이면 openDuration 동안 호출하지 않고 바로 LLM_UNAVAILABLE.
     * 장애 중에 요청마다 타임아웃(최대 45초)을 기다리며 스레드와 비용을 쓰지 않기 위해서.
     */
    public record CircuitBreaker(
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("10") int slidingWindowSize,
            @DefaultValue("4") int minimumNumberOfCalls,
            @DefaultValue("30s") Duration openDuration) {
    }
}
