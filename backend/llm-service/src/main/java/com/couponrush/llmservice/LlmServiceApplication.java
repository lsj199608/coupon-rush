package com.couponrush.llmservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * llm-service: LLM 호출 전담.
 * <pre>
 * llm-requests ─▶ LlmRequestConsumer ─▶ LlmRequestProcessor ─▶ LlmClient(fake | anthropic)
 *                                              └─▶ llm-results (COMPLETED | FAILED)
 * </pre>
 * 쿠폰 발급과 프로세스가 다르다 — LLM 이 느리거나 죽어도 coupon-api 의 스레드·커넥션을 붙잡지 않는다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LlmServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmServiceApplication.class, args);
    }
}
