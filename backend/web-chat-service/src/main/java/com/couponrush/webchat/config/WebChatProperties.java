package com.couponrush.webchat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

/**
 * web-chat-service 설정 (app.web-chat).
 *
 * @param endpoint       WebSocket(STOMP) 연결 주소
 * @param allowedOrigins 브라우저 Origin 허용 목록 (CSWSH 방지)
 * @param rateLimit      회원별 도배 제한
 * @param transport      프레임 크기·느린 클라이언트 제한
 * @param heartbeat      STOMP 하트비트 간격 (서버 → 클라이언트, 클라이언트 → 서버 같은 값)
 */
@ConfigurationProperties("app.web-chat")
public record WebChatProperties(
        @DefaultValue("/ws/web-chat") String endpoint,
        @DefaultValue("http://localhost:5173") List<String> allowedOrigins,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Transport transport,
        @DefaultValue("10s") Duration heartbeat) {

    public WebChatProperties {
        if (heartbeat.isNegative() || heartbeat.isZero()) {
            throw new IllegalArgumentException("app.web-chat.heartbeat 는 0보다 커야 합니다");
        }
    }

    /**
     * @param maxMessages window 안에 회원 한 명이 보낼 수 있는 메시지 수
     * @param window      세는 구간 (최근 window 동안 보낸 수 — 고정 창이 아니라 미끄러지는 창이라 경계에서 두 배로 몰리지 않는다)
     */
    public record RateLimit(@DefaultValue("5") int maxMessages,
                            @DefaultValue("10s") Duration window) {
        public RateLimit {
            if (maxMessages < 1) {
                throw new IllegalArgumentException("app.web-chat.rate-limit.max-messages 는 1 이상이어야 합니다");
            }
            if (window.isNegative() || window.isZero()) {
                throw new IllegalArgumentException("app.web-chat.rate-limit.window 는 0보다 커야 합니다");
            }
        }
    }

    /**
     * @param messageSizeLimit    받는 프레임 하나의 최대 크기
     * @param sendBufferSizeLimit 한 연결에 쌓아 둘 수 있는 보낼 데이터 (넘으면 연결 종료)
     * @param sendTimeLimit       한 연결에 보내는 시간 상한 (넘으면 연결 종료)
     */
    public record Transport(@DefaultValue("8KB") DataSize messageSizeLimit,
                            @DefaultValue("512KB") DataSize sendBufferSizeLimit,
                            @DefaultValue("10s") Duration sendTimeLimit) {
    }
}
