package com.couponrush.webchat.auth;

import com.couponrush.webchat.room.WebChatDestinations;
import com.couponrush.webchat.room.WebChatErrorCode;
import com.couponrush.webchat.room.WebChatRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import java.nio.charset.StandardCharsets;

/**
 * 클라이언트에서 들어오는 모든 STOMP 프레임의 입구 검사 (브로커·컨트롤러보다 먼저).
 * <ul>
 *   <li>CONNECT: {@link WebChatTicketAuthenticator}(ticket 헤더) 로 회원을 정해 세션 Principal 로 둔다</li>
 *   <li>SUBSCRIBE: 방(/topic/events/{id})과 내 오류(/user/queue/errors)만</li>
 *   <li>SEND: /app/events/{id}/chat 만, 로그인 연결만</li>
 * </ul>
 * 거절하면 그 프레임을 버리고(null 반환) ERROR 프레임(message 헤더 = 사유 코드)을 직접 보낸다 → Spring 이 보낸 뒤 연결을 닫는다.
 * 정상 화면이 보내지 않는 요청들이라 끊어도 된다. 메시지 내용 거절(길이·도배)은 컨트롤러에서 연결을 유지한 채 알린다.
 * <p>
 * 예외를 던지지 않는 이유: 같은 연결의 프레임 순서를 지키는 설정(preserveReceiveOrder)에서는 Spring 이 인터셉터 예외를
 * 로그만 남기고 삼켜서 ERROR 프레임이 나가지 않는다 (OrderedMessageChannelDecorator — 테스트에서 확인).
 */
@Component
public class StompInboundGuard implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompInboundGuard.class);
    private static final MimeType TEXT_PLAIN_UTF8 = new MimeType("text", "plain", StandardCharsets.UTF_8);

    private final WebChatTicketAuthenticator authenticator;
    private final MessageChannel clientOutboundChannel;

    /** clientOutboundChannel 은 이 인터셉터를 등록하는 WebSocket 설정이 만드는 Bean 이라 @Lazy 로 순환을 끊는다. */
    public StompInboundGuard(WebChatTicketAuthenticator authenticator,
                             @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel) {
        this.authenticator = authenticator;
        this.clientOutboundChannel = clientOutboundChannel;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // 하트비트 등
        }
        try {
            check(accessor);
            return message;
        } catch (WebChatRejectedException e) {
            log.info("채팅 프레임 거절: {} {} session={} destination={}", e.code(), accessor.getCommand(),
                    accessor.getSessionId(), shorten(accessor.getDestination()));
            sendError(accessor, e.code());
            return null;
        } catch (RuntimeException e) {
            log.error("채팅 프레임 처리 실패: {} session={}", accessor.getCommand(), accessor.getSessionId(), e);
            sendError(accessor, WebChatErrorCode.WEB_CHAT_ERROR);
            return null;
        }
    }

    private void check(StompHeaderAccessor accessor) {
        switch (accessor.getCommand()) {
            case CONNECT, STOMP -> authenticator.authenticate(accessor).ifPresent(accessor::setUser);
            case SUBSCRIBE -> {
                if (!WebChatDestinations.isSubscribable(accessor.getDestination())) {
                    throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_FORBIDDEN_DESTINATION);
                }
            }
            case SEND -> {
                if (!WebChatDestinations.isSendable(accessor.getDestination())) {
                    throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_FORBIDDEN_DESTINATION);
                }
                if (!(accessor.getUser() instanceof WebChatUser)) {
                    throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_LOGIN_REQUIRED);
                }
            }
            default -> {
                // UNSUBSCRIBE, DISCONNECT, ACK 등은 그대로
            }
        }
    }

    /** ERROR 프레임: message 헤더 = 사유 코드, 본문 = 고정 안내 문구 (내부 정보 없음). 받는 연결은 세션 ID 로 정해진다. */
    private void sendError(StompHeaderAccessor client, WebChatErrorCode code) {
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(code.name());
        error.setContentType(TEXT_PLAIN_UTF8);
        error.setSessionId(client.getSessionId());
        if (client.getReceipt() != null) {
            error.setReceiptId(client.getReceipt());
        }
        error.setLeaveMutable(true);
        clientOutboundChannel.send(MessageBuilder.createMessage(
                code.message().getBytes(StandardCharsets.UTF_8), error.getMessageHeaders()));
    }

    /** 주소는 클라이언트가 보낸 값이라 로그에는 길이를 잘라 남긴다. */
    private static String shorten(String destination) {
        return destination != null && destination.length() > 100 ? destination.substring(0, 100) + "…" : destination;
    }
}
