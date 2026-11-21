package com.couponrush.chatbot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 챗봇 대화방 (V8 chatbot_sessions). 회원 한 명의 것이고, 턴(ChatbotTurn)이 순서대로 쌓인다 */
@Entity
@Table(name = "chatbot_sessions")
public class ChatbotSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastMessageAt;

    protected ChatbotSession() {
    }

    public static ChatbotSession open(Long userId, Instant now) {
        ChatbotSession session = new ChatbotSession();
        session.userId = userId;
        session.createdAt = now;
        session.lastMessageAt = now;
        return session;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    /** 질문을 받은 시각 (목록 정렬용) */
    void touch(Instant now) {
        this.lastMessageAt = now;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastMessageAt() { return lastMessageAt; }
}
