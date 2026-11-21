package com.couponrush.chatbot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 대화의 한 턴 (V8 chatbot_turns) = 사용자 질문 + 그 질문의 LLM 요청(llmRequestId).
 * 답변·상태는 여기 없고 llm_requests 에서 읽는다 (원본 한 곳 — V8 주석).
 * <p>
 * 계약의 com.couponrush.llm.contract.ChatbotTurn(llm-service 로 보내는 지난 대화)과 이름이 같지만 다른 것이다:
 * 이쪽은 DB 행, 계약 쪽은 답을 받은 턴만 골라 만든 메시지 값.
 */
@Entity
@Table(name = "chatbot_turns")
public class ChatbotTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false, length = 500)
    private String question;

    @Column(nullable = false, unique = true)
    private UUID llmRequestId;

    @Column(nullable = false)
    private Instant createdAt;

    protected ChatbotTurn() {
    }

    public static ChatbotTurn of(Long sessionId, String question, UUID llmRequestId, Instant now) {
        ChatbotTurn turn = new ChatbotTurn();
        turn.sessionId = sessionId;
        turn.question = question;
        turn.llmRequestId = llmRequestId;
        turn.createdAt = now;
        return turn;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public String getQuestion() { return question; }
    public UUID getLlmRequestId() { return llmRequestId; }
    public Instant getCreatedAt() { return createdAt; }
}
