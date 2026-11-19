package com.couponrush.chat;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatTurnRepository extends JpaRepository<ChatTurn, Long> {

    /** 최근 턴부터 (id = 순서) */
    List<ChatTurn> findBySessionIdOrderByIdDesc(Long sessionId, Limit limit);

    Optional<ChatTurn> findByIdAndSessionId(Long id, Long sessionId);
}
