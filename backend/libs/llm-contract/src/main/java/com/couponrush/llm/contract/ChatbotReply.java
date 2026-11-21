package com.couponrush.llm.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

/**
 * 챗봇 답변 ({@link LlmResultMessage#chatbotReply()}). 사용자에게 그대로 보여 주는 글.
 * <p>
 * 답변에 나오는 이벤트·쿠폰 정보는 llm-service 가 도구(mcp-server → coupon-api)로 조회한 값이어야 한다 (C2).
 * 발급은 하지 않는다 — 원하면 이벤트 페이지 링크를 안내한다 ("발급 경로에 LLM 금지" 규칙).
 *
 * @param text 답변 (1~{@value #TEXT_MAX}자)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatbotReply(String text) {

    /** 답변 길이 상한 — 화면 말풍선과 다음 요청의 history 크기를 묶는다 */
    public static final int TEXT_MAX = 2000;

    public ChatbotReply {
        Objects.requireNonNull(text, "text");
        if (text.isBlank() || text.length() > TEXT_MAX) {
            throw new IllegalArgumentException("text 는 1~" + TEXT_MAX + "자여야 합니다");
        }
    }
}
