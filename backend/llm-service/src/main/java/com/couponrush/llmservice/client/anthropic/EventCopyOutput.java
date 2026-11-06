package com.couponrush.llmservice.client.anthropic;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 모델에게 요구하는 출력 형식. SDK 가 이 레코드에서 JSON 스키마를 만들어 구조화된 출력(output_config.format)으로 보낸다
 * → 모델 응답은 이 모양의 JSON 으로 강제된다. 길이·개수 규칙은 스키마가 아니라 {@link EventCopyOutputValidator} 가 검사한다.
 */
public record EventCopyOutput(
        @JsonPropertyDescription("이벤트 문구 후보. 정확히 3개")
        List<Candidate> candidates) {

    public record Candidate(
            @JsonPropertyDescription("이벤트 이름. 1~100자, 한국어")
            String name,
            @JsonPropertyDescription("이벤트 설명. 1~500자, 한국어")
            String description) {
    }
}
