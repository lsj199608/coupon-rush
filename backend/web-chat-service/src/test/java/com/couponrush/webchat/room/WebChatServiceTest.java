package com.couponrush.webchat.room;

import com.couponrush.webchat.auth.WebChatUser;
import com.couponrush.webchat.config.WebChatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

/** 보내기 규칙 — STOMP 연결 없이 (서비스는 STOMP 를 모른다). 발행은 목록에 담는 가짜, 저장 대기열은 mock. */
class WebChatServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T03:00:00Z");
    private static final WebChatUser SENDER = new WebChatUser(7, "회원7");

    private final List<WebChatMessage> published = new ArrayList<>();
    private final WebChatHistoryWriter historyWriter = Mockito.mock(WebChatHistoryWriter.class);
    private final WebChatService service = new WebChatService(
            new WebChatRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC), properties(3)), published::add, historyWriter,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 메시지는_서버가_보낸_사람_시각_ID_를_채워_발행한다() {
        WebChatMessage message = service.send(11, SENDER, "  안녕\n하세요  ");

        assertThat(published).containsExactly(message);
        assertThat(message.eventId()).isEqualTo(11);
        assertThat(message.senderId()).isEqualTo(7);
        assertThat(message.senderNickname()).isEqualTo("회원7");
        assertThat(message.content()).as("앞뒤 공백만 빼고 줄바꿈은 남긴다").isEqualTo("안녕\n하세요");
        assertThat(message.sentAt()).isEqualTo(NOW);
        assertThat(message.messageId()).hasSize(36);
        verify(historyWriter).enqueue(message);
    }

    @Test
    void 본문_길이는_코드_포인트로_센다() {
        String emoji500 = "😀".repeat(WebChatMessage.CONTENT_MAX); // UTF-16 으로는 1000
        assertThat(WebChatService.normalize(emoji500)).isEqualTo(emoji500);
        assertRejected(() -> WebChatService.normalize(emoji500 + "a"), WebChatErrorCode.WEB_CHAT_MESSAGE_TOO_LONG);
        assertRejected(() -> WebChatService.normalize("가".repeat(WebChatMessage.CONTENT_MAX + 1)),
                WebChatErrorCode.WEB_CHAT_MESSAGE_TOO_LONG);
    }

    @Test
    void 빈_본문과_없는_본문은_발행하지_않는다() {
        assertRejected(() -> service.send(11, SENDER, " \n\t "), WebChatErrorCode.WEB_CHAT_EMPTY_MESSAGE);
        assertRejected(() -> service.send(11, SENDER, null), WebChatErrorCode.WEB_CHAT_INVALID_MESSAGE);
        assertThat(published).isEmpty();
        verify(historyWriter, never()).enqueue(any());
    }

    @Test
    void 도배_한도를_넘으면_발행하지_않고_내용으로_거절된_메시지도_한도에_센다() {
        assertRejected(() -> service.send(11, SENDER, "가".repeat(WebChatMessage.CONTENT_MAX + 1)),
                WebChatErrorCode.WEB_CHAT_MESSAGE_TOO_LONG); // 1
        service.send(11, SENDER, "2");
        service.send(12, SENDER, "3");                       // 방이 달라도 회원 기준

        assertRejected(() -> service.send(11, SENDER, "4"), WebChatErrorCode.WEB_CHAT_RATE_LIMITED);
        assertThat(published).extracting(WebChatMessage::content).containsExactly("2", "3");
    }

    private static void assertRejected(Runnable call, WebChatErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(WebChatRejectedException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static WebChatProperties properties(int maxMessages) {
        return new WebChatProperties("/ws/web-chat", List.of("http://localhost:5173"),
                new WebChatProperties.RateLimit(maxMessages, Duration.ofSeconds(10)),
                new WebChatProperties.Transport(DataSize.ofKilobytes(8), DataSize.ofKilobytes(512), Duration.ofSeconds(10)),
                Duration.ofSeconds(10), new WebChatProperties.History(Duration.ofMillis(200), 500, 10000, 5,
                Duration.ofSeconds(1), 50, 100));
    }
}
