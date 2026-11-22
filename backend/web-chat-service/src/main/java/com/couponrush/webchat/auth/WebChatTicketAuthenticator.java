package com.couponrush.webchat.auth;

import com.couponrush.webchat.contract.WebChatTicket;
import com.couponrush.webchat.contract.WebChatTickets;
import com.couponrush.webchat.room.WebChatErrorCode;
import com.couponrush.webchat.room.WebChatRejectedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * STOMP CONNECT 의 ticket 헤더로 회원을 정한다 (7단계 D2). 티켓은 coupon-api 가 로그인 확인 후 발급한 1회용 값.
 * <ul>
 *   <li>헤더 없음 → 비로그인 연결 (읽기만 — 이벤트 페이지가 공개라서)</li>
 *   <li>헤더 있음 → Redis GETDEL(꺼내면서 지우기, 원자적) → 있으면 그 회원 / 없으면(만료·재사용·위조) 연결 거절</li>
 * </ul>
 * 잘못된 티켓을 비로그인으로 낮춰 받지 않는다 — 로그인한 줄 아는 사용자가 "보내기가 안 되는" 이상한 상태가 되는 것보다
 * 연결을 거절해 화면이 새 티켓으로 다시 연결하게 하는 편이 분명하다.
 * <p>
 * Redis 장애는 예외 그대로 → 입구 검사가 WEB_CHAT_ERROR 로 연결을 닫는다 (확인할 수 없으면 들이지 않는다).
 */
@Component
public class WebChatTicketAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(WebChatTicketAuthenticator.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public WebChatTicketAuthenticator(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * @return 회원 / 빈 값 = 비로그인 연결(읽기만)
     * @throws WebChatRejectedException 연결 거절 (WEB_CHAT_UNAUTHORIZED)
     */
    public Optional<WebChatUser> authenticate(StompHeaderAccessor connect) {
        String ticket = connect.getFirstNativeHeader(WebChatTickets.CONNECT_HEADER);
        if (ticket == null) {
            return Optional.empty();
        }
        if (!WebChatTickets.isWellFormed(ticket)) {
            throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_UNAUTHORIZED); // Redis 를 보기 전에
        }
        String value = redisTemplate.opsForValue().getAndDelete(WebChatTickets.redisKey(ticket));
        if (value == null) {
            throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_UNAUTHORIZED); // 만료·이미 사용·없는 티켓
        }
        WebChatTicket content = read(value);
        return Optional.of(new WebChatUser(content.userId(), content.nickname()));
    }

    private WebChatTicket read(String value) {
        try {
            return objectMapper.readValue(value, WebChatTicket.class);
        } catch (Exception e) {
            // 두 서비스의 계약이 어긋났다는 뜻 — 사용자 탓이 아니므로 ERROR 로그
            log.error("채팅 티켓 내용을 읽을 수 없습니다 (web-chat-contract 버전 확인)", e);
            throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_UNAUTHORIZED);
        }
    }
}
