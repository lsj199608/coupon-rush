package com.couponrush.llmservice.chat.tool;

/** 도구 서버 연결을 연다. 구현: {@link McpToolGateway} (mcp-server), 테스트에서는 가짜 */
public interface ToolGateway {

    /**
     * @param userId 질문한 회원 — 이 연결의 모든 도구 호출이 이 회원으로 처리된다
     * @throws com.couponrush.llmservice.client.LlmCallException 연결 실패·설정 누락 (LLM_UNAVAILABLE)
     */
    ToolSession open(long userId);
}
