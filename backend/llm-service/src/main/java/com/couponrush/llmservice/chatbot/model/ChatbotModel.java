package com.couponrush.llmservice.chatbot.model;

import com.couponrush.llm.contract.ChatbotInput;
import com.couponrush.llmservice.chatbot.tool.ToolSpec;

import java.time.LocalDate;
import java.util.List;

/**
 * 챗봇의 "판단"을 맡는 쪽 — 다음에 도구를 부를지, 무엇을 답할지. 반복(도구 실행·상한·마감)은 ChatbotOrchestrator 가 한다.
 * <ul>
 *   <li>{@link FakeChatbotModel} (기본) — 정해 둔 말 규칙으로 도구를 고르고, 도구 결과를 틀에 넣어 답한다 (LLM 호출 없음)</li>
 *   <li>client.anthropic.AnthropicChatbotModel — Claude tool use (LLM_PROVIDER=anthropic + 키)</li>
 * </ul>
 */
public interface ChatbotModel {

    /**
     * 질문 하나의 대화를 시작한다 (지난 대화 + 이번 질문 + 쓸 수 있는 도구).
     *
     * @param today 한국 날짜 ("이번 주" 같은 말의 기준) — 접수 시각 기준
     */
    ChatbotConversation start(ChatbotInput input, List<ToolSpec> tools, LocalDate today);
}
