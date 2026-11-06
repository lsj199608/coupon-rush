package com.couponrush.llmservice.client;

import com.couponrush.llmservice.LlmServiceProperties;
import com.couponrush.llmservice.client.anthropic.AnthropicLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Locale;

/**
 * 사용할 LLM 제공자를 고른다 (app.llm.provider = LLM_PROVIDER).
 * <ul>
 *   <li>fake (기본) → FakeLlmClient</li>
 *   <li>anthropic + API 키 있음 → AnthropicLlmClient</li>
 *   <li>anthropic + API 키 없음 → 경고 후 fake (키 없이 띄워도 흐름은 동작하게)</li>
 *   <li>그 외 값 → 기동 실패 (오타로 조용히 fake 가 되는 것을 막는다)</li>
 * </ul>
 * FakeLlmClient 는 항상 Bean 으로 존재하고(테스트에서 spy 하기 위해), 처리기는 이 @Primary LlmClient 를 주입받는다.
 */
@Configuration
public class LlmClientConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmClientConfig.class);

    public enum Provider { FAKE, ANTHROPIC }

    @Bean
    @Primary
    public LlmClient llmClient(LlmServiceProperties properties, FakeLlmClient fake, ObjectMapper objectMapper) {
        Provider provider = choose(properties.provider(), properties.anthropic().apiKey());
        if (provider == Provider.ANTHROPIC) {
            log.info("LLM 제공자: anthropic (model={}, effort={})",
                    properties.anthropic().model(), properties.anthropic().effort());
            return new AnthropicLlmClient(properties.anthropic(), properties.circuitBreaker(), objectMapper);
        }
        log.info("LLM 제공자: fake (API 키·비용 없음, 정해진 문구)");
        return fake;
    }

    public static Provider choose(String provider, String apiKey) {
        String normalized = provider == null ? "" : provider.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "fake" -> Provider.FAKE;
            case "anthropic" -> {
                if (apiKey == null || apiKey.isBlank()) {
                    log.warn("LLM_PROVIDER=anthropic 인데 LLM_API_KEY 가 비어 있음 → fake 로 동작");
                    yield Provider.FAKE;
                }
                yield Provider.ANTHROPIC;
            }
            default -> throw new IllegalArgumentException(
                    "알 수 없는 LLM_PROVIDER: '" + provider + "' (fake | anthropic)");
        };
    }
}
