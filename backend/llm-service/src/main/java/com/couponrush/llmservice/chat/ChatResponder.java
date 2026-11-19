package com.couponrush.llmservice.chat;

import com.couponrush.llm.contract.ChatInput;

import java.time.Instant;

/**
 * 챗봇 질문 하나 → 답변. 처리 흐름(멱등·마감·예산·결과 발행)은 LlmRequestProcessor 가 맡고, 이 인터페이스는 "답을 누가 만드느냐"만 담당한다.
 * <p>
 * 구현: {@link ChatOrchestrator} — 모델 ↔ mcp-server 도구를 오가는 tool use 루프 (도구 호출·토큰·마감 상한).
 */
public interface ChatResponder {

    /**
     * @param userId      질문한 회원 (coupon-api 가 JWT 에서 꺼낸 값, ≥ 1) — mcp-server 의 X-Mcp-User-Id 가 된다
     * @param requestedAt 접수 시각 ("이번 주" 같은 말의 기준 날짜)
     * @param deadline    이 시각이 지나면 coupon-api 가 TIMEOUT 으로 처리한다
     * @throws com.couponrush.llmservice.client.LlmCallException 실패 사유가 정해진 실패 (FAILED 결과가 된다)
     */
    ChatCompletion answer(long userId, ChatInput input, Instant requestedAt, Instant deadline);
}
