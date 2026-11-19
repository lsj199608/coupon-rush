package com.couponrush.llmservice.chat;

import com.couponrush.llm.contract.ChatInput;

/**
 * 챗봇 질문 하나 → 답변. 처리 흐름(멱등·마감·예산·결과 발행)은 LlmRequestProcessor 가 맡고, 이 인터페이스는 "답을 누가 만드느냐"만 담당한다.
 * <p>
 * 7단계 C1 은 {@link PlaceholderChatResponder}(임시 고정 답변)뿐이다. C2 에서 tool use 루프(ChatOrchestrator:
 * 모델 ↔ mcp-server 도구, 도구 호출 최대 5회·턴당 토큰 상한)로 바꾼다.
 */
public interface ChatResponder {

    /**
     * @param userId 질문한 회원 (coupon-api 가 JWT 에서 꺼낸 값, ≥ 1). C2 에서 mcp-server 의 X-Mcp-User-Id 가 된다
     * @throws com.couponrush.llmservice.client.LlmCallException 실패 사유가 정해진 실패 (FAILED 결과가 된다)
     */
    ChatCompletion answer(long userId, ChatInput input);
}
