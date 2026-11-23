package com.couponrush.webchat.room;

import com.couponrush.webchat.auth.WebChatUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보내기 규칙 — STOMP 연결 없이 (서비스는 STOMP 를 모른다). 발행은 목록에 담는 가짜, 저장 대기열·도배 제한은 mock
 * (도배 제한의 실제 셈은 Redis 로 WebChatRateLimiterTest 가 검증).
 */
class WebChatServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T03:00:00Z");
    private static final WebChatUser SENDER = new WebChatUser(7, "회원7");

    private final List<WebChatMessage> published = new ArrayList<>();
    private final WebChatRateLimiter rateLimiter = Mockito.mock(WebChatRateLimiter.class);
    private final WebChatHistoryWriter historyWriter = Mockito.mock(WebChatHistoryWriter.class);
    private final WebChatService service = new WebChatService(rateLimiter, published::add, historyWriter,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(rateLimiter.tryAcquire(anyLong())).thenReturn(true);
    }

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
        when(rateLimiter.tryAcquire(7)).thenReturn(true, true, true, false); // 한도 3

        assertRejected(() -> service.send(11, SENDER, "가".repeat(WebChatMessage.CONTENT_MAX + 1)),
                WebChatErrorCode.WEB_CHAT_MESSAGE_TOO_LONG); // 1 — 한도를 내용 검사보다 먼저 셌다
        service.send(11, SENDER, "2");
        service.send(12, SENDER, "3");                       // 방이 달라도 회원 기준

        assertRejected(() -> service.send(11, SENDER, "4"), WebChatErrorCode.WEB_CHAT_RATE_LIMITED);
        assertThat(published).extracting(WebChatMessage::content).containsExactly("2", "3");
    }

    @Test
    void 도배_제한을_확인할_수_없으면_발행하지_않는다() {
        when(rateLimiter.tryAcquire(7)).thenThrow(new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_UNAVAILABLE));

        assertRejected(() -> service.send(11, SENDER, "안녕"), WebChatErrorCode.WEB_CHAT_UNAVAILABLE);
        assertThat(published).isEmpty();
        verify(historyWriter, never()).enqueue(any());
    }

    @Test
    void 발행에_실패하면_저장하지_않고_보낸_사람에게_알린다() {
        WebChatService failing = new WebChatService(rateLimiter,
                message -> {
                    throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_UNAVAILABLE);
                },
                historyWriter, Clock.fixed(NOW, ZoneOffset.UTC));

        assertRejected(() -> failing.send(11, SENDER, "안녕"), WebChatErrorCode.WEB_CHAT_UNAVAILABLE);
        verify(historyWriter, never()).enqueue(any()); // 아무도 못 받은 메시지가 지난 대화에만 남지 않게
    }

    private static void assertRejected(Runnable call, WebChatErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(WebChatRejectedException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
