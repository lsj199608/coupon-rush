package com.couponrush.webchat.room;

import com.couponrush.webchat.config.WebChatProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.ServerSocket;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 도배 제한 Lua — 실제 Redis(Testcontainers, Spring 없음). 시각은 가짜 시계로 움직인다 (판정 시각은 앱이 넘기므로).
 * 이 테스트만 쓰는 컨테이너라 테스트마다 비운다.
 */
@Testcontainers
class WebChatRateLimiterTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-21T00:00:00Z"));
    private WebChatRateLimiter limiter;

    @BeforeAll
    static void connect() {
        connectionFactory = factory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        limiter = limiter(3, Duration.ofSeconds(10));
    }

    @Test
    void 창_안에서_최대_개수까지만_허용한다() {
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).as("같은 시각(같은 ms)이어도 따로 센다").isTrue();
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
        assertThat(redisTemplate.opsForZSet().zCard(WebChatRateLimiter.key(1))).isEqualTo(3);
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.tryAcquire(1)).isTrue();    // 처음 보낸 시각 기준으로 풀린다
    }

    @Test
    void 서버가_여러_대여도_한_장부로_센다() {
        WebChatRateLimiter serverA = limiter;
        WebChatRateLimiter serverB = limiter(3, Duration.ofSeconds(10)); // 같은 Redis 를 보는 다른 서버

        assertThat(serverA.tryAcquire(1)).isTrue();
        assertThat(serverB.tryAcquire(1)).isTrue();
        assertThat(serverA.tryAcquire(1)).isTrue();
        assertThat(serverB.tryAcquire(1)).as("두 서버 합쳐 3개").isFalse();
        assertThat(serverA.tryAcquire(1)).isFalse();
    }

    @Test
    void 키는_창_길이만큼_지나면_사라진다() {
        limiter.tryAcquire(1);

        Long ttl = redisTemplate.getExpire(WebChatRateLimiter.key(1), TimeUnit.MILLISECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(10_000); // 말 없는 회원의 기록이 Redis 에 남지 않는다
    }

    @Test
    void 같은_회원이_동시에_보내도_한도를_넘지_않는다() throws InterruptedException {
        WebChatRateLimiter limiter = limiter(5, Duration.ofMinutes(1));
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
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(allowed).hasValue(5);
    }

    @Test
    void Redis에_닿지_않으면_보내기를_거절한다() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort(); // 닫고 나면 아무도 듣지 않는 포트
        }
        LettuceConnectionFactory down = factory("localhost", closedPort);
        try {
            WebChatRateLimiter limiter = new WebChatRateLimiter(new StringRedisTemplate(down), clock,
                    properties(3, Duration.ofSeconds(10)));

            assertThatThrownBy(() -> limiter.tryAcquire(1))
                    .isInstanceOfSatisfying(WebChatRejectedException.class,
                            e -> assertThat(e.code()).isEqualTo(WebChatErrorCode.WEB_CHAT_UNAVAILABLE));
        } finally {
            down.destroy();
        }
    }

    private WebChatRateLimiter limiter(int maxMessages, Duration window) {
        return new WebChatRateLimiter(redisTemplate, clock, properties(maxMessages, window));
    }

    private static LettuceConnectionFactory factory(String host, int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static WebChatProperties properties(int maxMessages, Duration window) {
        return new WebChatProperties("/ws/web-chat", List.of("http://localhost:5173"),
                new WebChatProperties.RateLimit(maxMessages, window),
                new WebChatProperties.Transport(DataSize.ofKilobytes(8), DataSize.ofKilobytes(512), Duration.ofSeconds(10)),
                Duration.ofSeconds(10), new WebChatProperties.History(Duration.ofMillis(200), 500, 10000, 5,
                Duration.ofSeconds(1), 50, 100));
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
