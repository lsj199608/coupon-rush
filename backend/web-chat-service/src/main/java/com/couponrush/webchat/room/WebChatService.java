package com.couponrush.webchat.room;

import com.couponrush.webchat.auth.WebChatUser;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 방에 메시지 보내기 — 도배 제한 → 본문 규칙 → 메시지 만들기(ID·시각·보낸 사람은 서버 값) → 발행 → 저장 대기열(D6).
 * STOMP 를 모른다 (프레임·주소·세션은 컨트롤러와 입구 검사의 일) → 연결 없이 단위 테스트할 수 있다.
 * <p>
 * <b>방에 먼저 보내고 저장은 나중에</b>: 저장({@link WebChatHistoryWriter})은 대기열에 넣기만 해서 DB 가 느리거나 죽어도
 * 대화는 그대로 오간다. 저장이 실패하면 그 메시지는 지난 대화에만 없다.
 * <p>
 * <b>저장은 메시지를 받은 이 서버가 한 번만</b> (D3): 다른 서버는 Redis 로 전달받아 방에 보내기만 한다({@link WebChatRelayListener}).
 * 발행이 실패하면(아무도 못 받음) 저장하지 않는다 — 지난 대화에만 있고 아무도 못 본 메시지가 생기지 않게.
 * <p>
 * 이벤트가 실제로 있는지는 확인하지 않는다 (메시지마다 coupon-api 를 부르면 무거움).
 * 없는 이벤트 방은 아무도 보지 않는 빈 방일 뿐이다 — 필요하면 D2 티켓 발급 때 coupon-api 가 확인하게 한다.
 */
@Service
public class WebChatService {

    private final WebChatRateLimiter rateLimiter;
    private final WebChatMessagePublisher publisher;
    private final WebChatHistoryWriter historyWriter;
    private final Clock clock;

    public WebChatService(WebChatRateLimiter rateLimiter, WebChatMessagePublisher publisher,
                          WebChatHistoryWriter historyWriter, Clock clock) {
        this.rateLimiter = rateLimiter;
        this.publisher = publisher;
        this.historyWriter = historyWriter;
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
        // 시각은 마이크로초로 자른다 — PostgreSQL 정밀도. 안 자르면 실시간으로 받은 시각과 지난 대화의 시각이 달라진다
        WebChatMessage message = new WebChatMessage(UUID.randomUUID().toString(), eventId, sender.userId(),
                sender.nickname(), normalize(content), clock.instant().truncatedTo(ChronoUnit.MICROS));
        publisher.publish(message);
        historyWriter.enqueue(message);
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
