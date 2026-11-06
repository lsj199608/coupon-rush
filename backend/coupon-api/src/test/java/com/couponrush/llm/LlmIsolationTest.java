package com.couponrush.llm;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CLAUDE.md 규칙 "쿠폰 발급 핵심 경로에는 LLM 호출을 절대 넣지 않는다"를 문서가 아니라 테스트로 강제한다.
 * coupon-api 클래스패스에 LLM 클라이언트 라이브러리가 들어오면(누가 build.gradle 에 추가하거나, 다른 의존성이 끌고 오면) 실패한다.
 * LLM 호출은 llm-service 에서만 — coupon-api 는 Kafka 메시지(llm-contract)만 안다.
 */
class LlmIsolationTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "org/springframework/ai/",        // Spring AI 전체
            "com/anthropic/",                 // Anthropic Java SDK
            "com/openai/",                    // OpenAI Java SDK
            "dev/langchain4j/"                // LangChain4j
    })
    void LLM_라이브러리_패키지가_클래스패스에_없다(String packagePath) {
        assertThat(getClass().getClassLoader().getResource(packagePath))
                .as("coupon-api 에 LLM 라이브러리가 들어왔다: " + packagePath + " — LLM 호출은 llm-service 에서만")
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "org.springframework.ai.chat.client.ChatClient",
            "org.springframework.ai.chat.model.ChatModel",
            "org.springframework.ai.anthropic.AnthropicChatModel"
    })
    void LLM_클라이언트_클래스를_불러올_수_없다(String className) {
        // 패키지 디렉터리 항목이 없는 jar 도 있어서 대표 클래스를 직접 한 번 더 확인한다
        assertThatThrownBy(() -> Class.forName(className)).isInstanceOf(ClassNotFoundException.class);
    }

    @org.junit.jupiter.api.Test
    void 계약_모듈은_있다() throws Exception {
        assertThat(Class.forName("com.couponrush.llm.contract.LlmRequestMessage")).isNotNull();
    }
}
