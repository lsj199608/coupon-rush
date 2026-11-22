package com.couponrush.webchat.room;

import com.couponrush.webchat.config.WebChatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장 대기열 규칙 — 저장소는 가짜(실패를 시킬 수 있는 목록), 시계는 직접 움직인다. 스레드는 띄우지 않고 flush() 를 직접 부른다.
 */
class WebChatHistoryWriterTest {

    private static final Instant NOW = Instant.parse("2026-11-22T03:00:00Z");

    private final FakeRepository repository = new FakeRepository();
    private final WebChatRateLimiterTest.MutableClock clock = new WebChatRateLimiterTest.MutableClock(NOW);

    @Test
    void 쌓인_메시지를_순서대로_batch_size_씩_묶어_저장한다() {
        WebChatHistoryWriter writer = writer(2, 100, 3);
        List<WebChatMessage> messages = List.of(message("1"), message("2"), message("3"), message("4"), message("5"));
        messages.forEach(writer::enqueue);

        writer.flush();

        assertThat(repository.batches).extracting(List::size).containsExactly(2, 2, 1);
        assertThat(repository.saved()).containsExactlyElementsOf(messages);
        assertThat(writer.savedCount()).isEqualTo(5);
    }

    @Test
    void 대기열이_차면_저장만_포기하고_예외를_던지지_않는다() {
        WebChatHistoryWriter writer = writer(10, 2, 3);

        writer.enqueue(message("1"));
        writer.enqueue(message("2"));
        writer.enqueue(message("3")); // 넘침 — 대화(방 전송)는 이미 끝났으므로 조용히 버린다

        writer.flush();
        assertThat(repository.saved()).extracting(WebChatMessage::content).containsExactly("1", "2");
        assertThat(writer.droppedCount()).isEqualTo(1);
    }

    @Test
    void 실패한_묶음은_간격을_두고_다시_저장하고_그동안_뒤_메시지가_앞지르지_않는다() {
        WebChatHistoryWriter writer = writer(10, 100, 5); // retry-delay 1s, 2배씩
        writer.enqueue(message("1"));
        repository.failNext(2);

        writer.flush();                                   // 1회 실패 → 1초 뒤
        writer.enqueue(message("2"));
        clock.advance(Duration.ofMillis(999));
        writer.flush();                                   // 아직 재시도 시각 전 — 2도 저장하지 않음
        assertThat(repository.attempts).isEqualTo(1);
        assertThat(repository.saved()).isEmpty();

        clock.advance(Duration.ofMillis(1));
        writer.flush();                                   // 2회 실패 → 2초 뒤
        clock.advance(Duration.ofMillis(1999));
        writer.flush();
        assertThat(repository.attempts).isEqualTo(2);

        clock.advance(Duration.ofMillis(1));
        writer.flush();                                   // 성공 → 이어서 2도
        assertThat(repository.saved()).extracting(WebChatMessage::content).containsExactly("1", "2");
        assertThat(writer.failedAttemptCount()).isEqualTo(2);
        assertThat(writer.droppedCount()).isZero();
    }

    @Test
    void 시도_횟수를_넘긴_묶음은_버리고_다음_메시지는_저장한다() {
        WebChatHistoryWriter writer = writer(10, 100, 2);
        writer.enqueue(message("버려질"));
        repository.failNext(2);

        writer.flush();
        clock.advance(Duration.ofSeconds(1));
        writer.flush();                                   // 2회째 실패 = 한도 → 버림
        writer.enqueue(message("다음"));
        writer.flush();

        assertThat(repository.saved()).extracting(WebChatMessage::content).containsExactly("다음");
        assertThat(writer.droppedCount()).isEqualTo(1);
    }

    @Test
    void 정상_종료하면_남은_메시지를_저장한다() {
        WebChatHistoryWriter writer = writer(10, 100, 3);
        writer.start();
        writer.enqueue(message("마지막"));

        writer.stop();

        assertThat(repository.saved()).extracting(WebChatMessage::content).containsExactly("마지막");
        assertThat(writer.isRunning()).isFalse();
    }

    private WebChatHistoryWriter writer(int batchSize, int queueCapacity, int maxAttempts) {
        WebChatProperties properties = new WebChatProperties("/ws/web-chat", List.of("http://localhost:5173"),
                new WebChatProperties.RateLimit(5, Duration.ofSeconds(10)),
                new WebChatProperties.Transport(DataSize.ofKilobytes(8), DataSize.ofKilobytes(512), Duration.ofSeconds(10)),
                Duration.ofSeconds(10),
                // 저장 스레드 간격은 길게 — 테스트가 flush() 를 직접 부른다
                new WebChatProperties.History(Duration.ofHours(1), batchSize, queueCapacity, maxAttempts,
                        Duration.ofSeconds(1), 50, 100));
        return new WebChatHistoryWriter(repository, properties, clock);
    }

    private static WebChatMessage message(String content) {
        return new WebChatMessage(UUID.randomUUID().toString(), 1, 7, "회원7", content, NOW);
    }

    /** 저장한 묶음을 기억하고, 지정한 횟수만큼 실패한다. */
    static final class FakeRepository extends WebChatMessageRepository {
        final List<List<WebChatMessage>> batches = new ArrayList<>();
        int attempts;
        private int failures;

        FakeRepository() {
            super(null);
        }

        void failNext(int count) {
            failures = count;
        }

        List<WebChatMessage> saved() {
            return batches.stream().flatMap(List::stream).toList();
        }

        @Override
        public void saveAll(List<WebChatMessage> messages) {
            attempts++;
            if (failures > 0) {
                failures--;
                throw new DataAccessResourceFailureException("DB 연결 실패 (테스트)");
            }
            batches.add(List.copyOf(messages));
        }
    }
}
