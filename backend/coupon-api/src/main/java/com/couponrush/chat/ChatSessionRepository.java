package com.couponrush.chat;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /**
     * 질문 접수 때 대화방 행을 잠근다 (SELECT ... FOR UPDATE) — 같은 방에 질문 두 개가 동시에 와도 하나씩 처리돼
     * "답을 기다리는 질문은 하나만" 검사가 함께 통과하지 않는다. 다른 방·다른 회원은 막지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChatSession s where s.id = :id")
    Optional<ChatSession> findByIdForUpdate(@Param("id") Long id);

    /**
     * 내 대화방 목록 (최근 대화 순) + 첫 질문(제목 대신). JPQL 생성자 표현식이라 시각 타입(Instant)이 엔티티와 같게 매핑된다.
     * 첫 질문은 방마다 min(id) 턴 — 턴 인덱스(session_id, id)를 탄다.
     */
    @Query("""
            select new com.couponrush.chat.ChatSessionSummary(s.id, s.createdAt, s.lastMessageAt,
                   (select t.question from ChatTurn t
                     where t.sessionId = s.id
                       and t.id = (select min(t2.id) from ChatTurn t2 where t2.sessionId = s.id)))
              from ChatSession s
             where s.userId = :userId
             order by s.lastMessageAt desc, s.id desc
            """)
    List<ChatSessionSummary> findSummaries(@Param("userId") Long userId, Limit limit);
}
