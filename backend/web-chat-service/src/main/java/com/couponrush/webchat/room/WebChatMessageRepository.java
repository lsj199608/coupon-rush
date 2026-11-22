package com.couponrush.webchat.room;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * web_chat_messages 테이블 (D6, 데이터베이스 web_chat). JPA 없이 JDBC — 묶음 INSERT 와 커서 조회 두 가지뿐이라서.
 */
@Repository
public class WebChatMessageRepository {

    private static final String INSERT = """
            INSERT INTO web_chat_messages (message_id, event_id, sender_id, sender_nickname, content, sent_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (message_id) DO NOTHING
            """;

    private static final String COLUMNS = "id, message_id, event_id, sender_id, sender_nickname, content, sent_at";

    private static final RowMapper<WebChatHistoryMessage> ROW = (rs, n) -> new WebChatHistoryMessage(
            rs.getLong("id"), rs.getString("message_id"), rs.getLong("event_id"), rs.getLong("sender_id"),
            rs.getString("sender_nickname"), rs.getString("content"), rs.getTimestamp("sent_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public WebChatMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 묶음 INSERT (JDBC 배치로 함께 전송). 트랜잭션 없이 문장마다 반영되므로 중간에 실패하면 앞부분만 들어갈 수 있다 —
     * 그 묶음을 다시 넣어도 같은 message_id 는 건너뛰어(ON CONFLICT DO NOTHING) 두 번 저장되지 않는다.
     */
    public void saveAll(List<WebChatMessage> messages) {
        jdbcTemplate.batchUpdate(INSERT, messages, messages.size(), (ps, m) -> {
            ps.setObject(1, UUID.fromString(m.messageId()));
            ps.setLong(2, m.eventId());
            ps.setLong(3, m.senderId());
            ps.setString(4, m.senderNickname());
            ps.setString(5, m.content());
            ps.setTimestamp(6, Timestamp.from(m.sentAt()));
        });
    }

    /** 방의 메시지를 최신부터 limit 개 (beforeId 가 있으면 그보다 오래된 것만). */
    public List<WebChatHistoryMessage> findLatest(long eventId, Long beforeId, int limit) {
        if (beforeId == null) {
            return jdbcTemplate.query("SELECT " + COLUMNS + " FROM web_chat_messages WHERE event_id = ? ORDER BY id DESC LIMIT ?",
                    ROW, eventId, limit);
        }
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM web_chat_messages WHERE event_id = ? AND id < ? ORDER BY id DESC LIMIT ?",
                ROW, eventId, beforeId, limit);
    }
}
