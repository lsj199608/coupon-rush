package com.couponrush.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 챗봇 대화방 (V8 chat_sessions). 회원 한 명의 것이고, 턴(ChatTurn)이 순서대로 쌓인다 */
@Entity
@Table(name = "chat_sessions")
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastMessageAt;

    protected ChatSession() {
    }

    public static ChatSession open(Long userId, Instant now) {
        ChatSession session = new ChatSession();
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
