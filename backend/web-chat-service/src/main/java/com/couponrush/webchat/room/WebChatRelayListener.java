package com.couponrush.webchat.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Redis 채널 {@value WebChatRelay#CHANNEL} 에서 받은 메시지를 이 서버의 단순 브로커로 방 구독자에게 보낸다 (자기가 보낸 메시지 포함).
 * <p>
 * <b>저장하지 않는다</b> — 저장은 보낸 서버가 한 번만({@link WebChatService}). 받는 쪽마다 저장하면 서버 수만큼 INSERT 가 된다
 * (message_id UNIQUE 가 중복 행은 막지만 쓰기는 그대로 N배).
 * <p>
 * 읽을 수 없는 메시지(형식 오류, 배포 중 다른 버전이 보낸 모양)는 WARN 후 버린다 — 예외를 올려도 다시 받을 방법이 없다(pub/sub).
 * 이 리스너는 단일 스레드에서 차례로 불린다(WebChatRelayConfig) — 받은 순서 = 방에 보내는 순서.
 */
@Component
public class WebChatRelayListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(WebChatRelayListener.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public WebChatRelayListener(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        WebChatMessage chat;
        try {
            chat = objectMapper.readValue(message.getBody(), WebChatMessage.class);
        } catch (IOException | RuntimeException e) { // RuntimeException: 레코드 생성자 검사(필수 값 없음)
            log.warn("읽을 수 없는 채팅 전달 메시지를 버립니다: {}", e.getMessage());
            return;
        }
        messagingTemplate.convertAndSend(WebChatDestinations.room(chat.eventId()), chat);
    }
}
