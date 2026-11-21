package com.couponrush.webchat.room;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** 이 서버의 단순 브로커로 보낸다 (서버 1대 기준, D1). */
@Component
public class LocalWebChatMessagePublisher implements WebChatMessagePublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public LocalWebChatMessagePublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publish(WebChatMessage message) {
        messagingTemplate.convertAndSend(WebChatDestinations.room(message.eventId()), message);
    }
}
