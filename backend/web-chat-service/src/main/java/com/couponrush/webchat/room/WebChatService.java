package com.couponrush.webchat.room;

import com.couponrush.webchat.auth.WebChatUser;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * 방에 메시지 보내기 — 도배 제한 → 본문 규칙 → 메시지 만들기(ID·시각·보낸 사람은 서버 값) → 발행.
 * STOMP 를 모른다 (프레임·주소·세션은 컨트롤러와 입구 검사의 일) → 연결 없이 단위 테스트할 수 있다.
 * <p>
 * 이벤트가 실제로 있는지는 확인하지 않는다 (DB 없음, 메시지마다 coupon-api 를 부르면 무거움).
 * 없는 이벤트 방은 아무도 보지 않는 빈 방일 뿐이다 — 필요하면 D2 티켓 발급 때 coupon-api 가 확인하게 한다.
 */
@Service
public class WebChatService {

    private final WebChatRateLimiter rateLimiter;
    private final WebChatMessagePublisher publisher;
    private final Clock clock;

    public WebChatService(WebChatRateLimiter rateLimiter, WebChatMessagePublisher publisher, Clock clock) {
        this.rateLimiter = rateLimiter;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * @param content 클라이언트가 보낸 본문 그대로
     * @return 방에 보낸 메시지
     * @throws WebChatRejectedException 도배 한도 초과, 빈 본문, {@value WebChatMessage#CONTENT_MAX}자 초과
     */
    public WebChatMessage send(long eventId, WebChatUser sender, String content) {
        // 도배 제한을 내용 검사보다 먼저: 잘못된 메시지를 연타해도 처리 비용이 한도 안으로 묶이게
        if (!rateLimiter.tryAcquire(sender.userId())) {
            throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_RATE_LIMITED);
        }
        WebChatMessage message = new WebChatMessage(UUID.randomUUID().toString(), eventId, sender.userId(),
                sender.nickname(), normalize(content), clock.instant());
        publisher.publish(message);
        return message;
    }

    /** 앞뒤 공백을 빼고(줄바꿈은 남김) 길이를 코드 포인트로 센다 — 이모지 하나를 두 글자로 세지 않게. */
    static String normalize(String content) {
        if (content == null) {
            throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_INVALID_MESSAGE);
        }
        String text = content.strip();
        if (text.isEmpty()) {
            throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_EMPTY_MESSAGE);
        }
        if (text.codePointCount(0, text.length()) > WebChatMessage.CONTENT_MAX) {
            throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_MESSAGE_TOO_LONG);
        }
        return text;
    }
}
