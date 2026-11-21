package com.couponrush.webchat.room;

import com.couponrush.webchat.config.WebChatProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WebChatRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-21T00:00:00Z"));
    private WebChatRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new WebChatRateLimiter(clock, properties(3, Duration.ofSeconds(10)));
    }

    @Test
    void 창_안에서_최대_개수까지만_허용한다() {
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isFalse();
    }

    @Test
    void 회원마다_따로_센다() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(1);
        }
        assertThat(limiter.tryAcquire(1)).isFalse();
        assertThat(limiter.tryAcquire(2)).isTrue();
    }

    @Test
    void 가장_오래된_메시지가_창을_벗어나면_한_개씩_풀린다() {
        limiter.tryAcquire(1);                 // t=0
        clock.advance(Duration.ofSeconds(4));
        limiter.tryAcquire(1);                 // t=4
        limiter.tryAcquire(1);                 // t=4
        assertThat(limiter.tryAcquire(1)).isFalse();

        clock.advance(Duration.ofSeconds(6));  // t=10: t=0 메시지가 정확히 창 끝 → 빠짐 (창은 (now-10s, now])
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isFalse(); // t=4 두 개는 아직 창 안 — 고정 창처럼 한꺼번에 풀리지 않는다
    }

    @Test
    void 한도로_거절된_시도는_세지_않는다() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(1);
        }
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire(1)).isFalse(); // 계속 눌러도
        }
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.tryAcquire(1)).isTrue();    // 처음 보낸 시각 기준으로 풀린다
    }

    @Test
    void 정리는_창이_빈_회원만_지운다() {
        limiter.tryAcquire(1);
        clock.advance(Duration.ofSeconds(6));
        limiter.tryAcquire(2);
        clock.advance(Duration.ofSeconds(5));  // 1 의 기록은 11초 전, 2 는 5초 전

        limiter.sweep();

        assertThat(limiter.trackedUsers()).isEqualTo(1);
        assertThat(limiter.tryAcquire(2)).isTrue();
        assertThat(limiter.tryAcquire(2)).isTrue();
        assertThat(limiter.tryAcquire(2)).isFalse(); // 2 의 기존 기록은 남아 있다
    }

    @Test
    void 같은_회원이_동시에_보내도_한도를_넘지_않는다() throws InterruptedException {
        WebChatRateLimiter limiter = new WebChatRateLimiter(clock, properties(5, Duration.ofMinutes(1)));
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        try {
            for (int i = 0; i < threads * 10; i++) {
                pool.submit(() -> {
                    start.await();
                    if (limiter.tryAcquire(7)) {
                        allowed.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(allowed).hasValue(5);
    }

    private static WebChatProperties properties(int maxMessages, Duration window) {
        return new WebChatProperties("/ws/web-chat", List.of("http://localhost:5173"),
                new WebChatProperties.RateLimit(maxMessages, window),
                new WebChatProperties.Transport(DataSize.ofKilobytes(8), DataSize.ofKilobytes(512), Duration.ofSeconds(10)),
                Duration.ofSeconds(10));
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
