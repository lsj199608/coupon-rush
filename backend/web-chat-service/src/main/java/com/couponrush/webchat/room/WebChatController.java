package com.couponrush.webchat.room;

import com.couponrush.webchat.auth.WebChatUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * STOMP 입구: SEND /app/events/{eventId}/chat 을 받아 {@link WebChatService} 에 넘기고, 거절은 보낸 세션에 사유로 돌려준다.
 * <p>
 * 주소·로그인 여부는 그 앞의 {@link com.couponrush.webchat.auth.StompInboundGuard} 가 이미 확인했다.
 * 내용(길이)·도배 거절은 연결을 끊지 않고 /user/queue/errors 로 알린다.
 */
@Controller
public class WebChatController {

    private static final Logger log = LoggerFactory.getLogger(WebChatController.class);

    private final WebChatService webChatService;

    public WebChatController(WebChatService webChatService) {
        this.webChatService = webChatService;
    }

    @MessageMapping("/events/{eventId}/chat")
    public void send(@DestinationVariable long eventId, @Payload WebChatSendRequest request, Principal principal) {
        if (!(principal instanceof WebChatUser user)) {
            // 가드가 비로그인 SEND 를 막으므로 오지 않는다 — 혹시 경로가 바뀌어도 열린 채로 두지 않는다
            throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_LOGIN_REQUIRED);
        }
        webChatService.send(eventId, user, request.content());
    }

    /** 거절 사유를 보낸 세션에만 (broadcast=false — 같은 회원의 다른 탭에는 보내지 않음). */
    @MessageExceptionHandler
    @SendToUser(destinations = WebChatDestinations.ERRORS, broadcast = false)
    public WebChatError handleRejected(WebChatRejectedException e) {
        return WebChatError.of(e.code());
    }

    /** 바디가 JSON 이 아니거나 모양이 다름. */
    @MessageExceptionHandler
    @SendToUser(destinations = WebChatDestinations.ERRORS, broadcast = false)
    public WebChatError handleUnreadable(MessageConversionException e) {
        log.debug("읽을 수 없는 채팅 메시지: {}", e.getMessage());
        return WebChatError.of(WebChatErrorCode.WEB_CHAT_INVALID_MESSAGE);
    }
}
