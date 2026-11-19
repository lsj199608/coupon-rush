package com.couponrush.llm.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

/**
 * 챗봇 질문 (7단계 C). 사용자가 채팅 창에 쓴 문장 + 같은 대화방의 최근 턴.
 * <p>
 * <b>누가 묻는지는 여기에 없다</b> — {@link LlmRequestMessage#requestedBy()}(coupon-api 가 JWT 에서 꺼낸 회원 ID)만 믿는다.
 * llm-service 는 그 값을 mcp-server 의 X-Mcp-User-Id 로 넘긴다 (질문 문장에 "나는 7번 회원"이라고 써도 소용없다).
 * 문장과 지난 대화는 프롬프트에 <b>데이터로만</b> 들어간다.
 * <p>
 * 대화 기록은 coupon-api DB(chat_turns)에 있고 llm-service 는 DB 가 없으므로 요청마다 최근 턴을 함께 보낸다.
 *
 * @param message 이번 질문 (1~{@value #MESSAGE_MAX}자)
 * @param history 지난 턴, 오래된 것부터 최대 {@value #HISTORY_MAX}개 (첫 질문이면 빈 목록)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatInput(String message, List<ChatTurn> history) {

    /** 질문 길이 상한 — 프롬프트(=비용) 크기를 묶는다. coupon-api 요청 검증도 이 값을 쓴다 */
    public static final int MESSAGE_MAX = 500;
    /** 함께 보내는 지난 턴 수 상한 — 대화가 길어져도 요청 크기가 일정하게 */
    public static final int HISTORY_MAX = 10;

    public ChatInput {
        Objects.requireNonNull(message, "message");
        if (message.isBlank() || message.length() > MESSAGE_MAX) {
            throw new IllegalArgumentException("message 는 1~" + MESSAGE_MAX + "자여야 합니다");
        }
        history = history == null ? List.of() : List.copyOf(history);
        if (history.size() > HISTORY_MAX) {
            throw new IllegalArgumentException("history 는 최대 " + HISTORY_MAX + "개입니다");
        }
    }
}
