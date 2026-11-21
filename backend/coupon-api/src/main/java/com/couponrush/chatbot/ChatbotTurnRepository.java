package com.couponrush.chatbot;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatbotTurnRepository extends JpaRepository<ChatbotTurn, Long> {

    /** 최근 턴부터 (id = 순서) */
    List<ChatbotTurn> findBySessionIdOrderByIdDesc(Long sessionId, Limit limit);

    Optional<ChatbotTurn> findByIdAndSessionId(Long id, Long sessionId);
}
