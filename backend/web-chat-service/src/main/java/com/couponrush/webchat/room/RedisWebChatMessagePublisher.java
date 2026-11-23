package com.couponrush.webchat.room;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 메시지를 Redis 채널 {@value WebChatRelay#CHANNEL} 에 PUBLISH 한다 (D3, 서버 여러 대).
 * <p>
 * <b>보낸 서버도 자기 브로커에 직접 넣지 않고 Redis 를 거쳐 받는다</b> ({@link WebChatRelayListener}):
 * Redis 는 한 채널의 메시지를 모든 구독자에게 같은 순서로 전달하므로, 어느 서버에 붙은 사람이든 방의 대화 순서가 같다.
 * 직접 넣고 다른 서버에만 보내면 서버마다 "자기 메시지가 먼저"인 순서가 되어 화면마다 대화 순서가 달라진다.
 * 대가는 왕복 1번(로컬 수 ms)과, Redis 가 죽으면 같은 서버 안의 대화도 멈춘다는 것 — 연결 티켓(D2)도 Redis 라 이미 같은 의존이다.
 * <p>
 * 발행 실패는 {@link WebChatErrorCode#WEB_CHAT_UNAVAILABLE} 로 보낸 사람에게만 알린다 (연결 유지, 저장하지 않음 — 아무도 받지 못한 메시지).
 */
@Component
public class RedisWebChatMessagePublisher implements WebChatMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisWebChatMessagePublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisWebChatMessagePublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(WebChatMessage message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("채팅 메시지를 JSON 으로 만들 수 없습니다", e); // 레코드라 일어나지 않음
        }
        try {
            redisTemplate.convertAndSend(WebChatRelay.CHANNEL, json);
        } catch (DataAccessException e) {
            log.warn("채팅 메시지 발행 실패 (Redis) eventId={}: {}", message.eventId(), e.getMessage());
            throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_UNAVAILABLE);
        }
    }
}
