package com.couponrush.webchat;

import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.couponrush.user.User;
import com.couponrush.user.UserService;
import com.couponrush.webchat.contract.WebChatTicket;
import com.couponrush.webchat.contract.WebChatTickets;
import com.couponrush.webchat.dto.WebChatTicketResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * 채팅방(web-chat-service) 연결용 1회용 티켓 발급 (7단계 D2).
 * <p>
 * 왜 티켓인가: 브라우저 WebSocket 은 Authorization 헤더를 붙일 수 없고, URL 에 토큰을 싣지 않는 규칙이 있다.
 * 그래서 로그인 확인은 여기(coupon-api, JWT)에서 하고, 짧게 사는 1회용 값만 첫 STOMP 프레임으로 넘긴다.
 * web-chat-service 는 JWT 비밀키도 users 테이블도 모른다 — Redis 에서 티켓을 꺼내면 누구인지 안다.
 * <p>
 * 보낸 사람·닉네임은 여기서 정해 티켓에 담는다 → 채팅에서 사칭할 수 없다. 닉네임은 발급 시점 값.
 */
@Service
public class WebChatTicketService {

    private static final Logger log = LoggerFactory.getLogger(WebChatTicketService.class);

    private final UserService userService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WebChatTicketService(UserService userService, StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                Clock clock) {
        this.userService = userService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public WebChatTicketResponse issue(long userId) {
        User user = userService.getUser(userId); // 탈퇴 등으로 없으면 404 USER_NOT_FOUND
        Instant now = clock.instant();
        String value = toJson(new WebChatTicket(user.getId(), user.getNickname(), now));
        String ticket = newTicket();
        try {
            // SET NX: 32바이트 무작위 값이라 겹칠 일은 없지만, 겹쳐도 남의 티켓을 덮어쓰지 않게
            Boolean saved = redisTemplate.opsForValue().setIfAbsent(WebChatTickets.redisKey(ticket), value, WebChatTickets.TTL);
            if (!Boolean.TRUE.equals(saved)) {
                throw new IllegalStateException("티켓 키가 이미 있습니다");
            }
        } catch (DataAccessException | IllegalStateException e) {
            log.error("채팅 티켓 저장 실패: userId={}", userId, e);
            throw new BusinessException(ErrorCode.WEB_CHAT_TICKET_UNAVAILABLE);
        }
        return new WebChatTicketResponse(ticket, now.plus(WebChatTickets.TTL));
    }

    private String newTicket() {
        byte[] bytes = new byte[WebChatTickets.RANDOM_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String toJson(WebChatTicket ticket) {
        try {
            return objectMapper.writeValueAsString(ticket);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("티켓 직렬화 실패", e);
        }
    }
}
