package com.couponrush.webchat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 이벤트별 실시간 채팅방 (7단계 D). 같은 이벤트 페이지를 보는 사람끼리 대화한다.
 * <pre>
 * [브라우저] ─ ws /ws/web-chat (STOMP) ─▶ [web-chat-service]
 *   SUBSCRIBE /topic/events/{id}          방 메시지 받기 (비로그인도 가능 — 이벤트 페이지가 공개라서)
 *   SEND      /app/events/{id}/chat       보내기 (로그인만, 보낸 사람은 서버가 채움)
 *   SUBSCRIBE /user/queue/errors          내 메시지가 거절된 이유 (너무 김·도배 제한)
 * </pre>
 * 발급과 무관하다 (조회·대화만). D1 은 서버 1대 기준 — 여러 대 사이 전달은 D3(Redis pub/sub).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class WebChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebChatServiceApplication.class, args);
    }
}
