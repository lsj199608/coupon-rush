package com.couponrush.llm;

import com.couponrush.IntegrationTest;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

/** 실제 Redis 로 한도 검증. 시각을 인자로 넘겨 분·일 경계를 결정적으로 만든다 (기본 분당 5 / 일 50, Asia/Seoul) */
@IntegrationTest
class LlmRateLimiterTest {

    // 2026-11-06 13:30:00 KST
    private static final Instant T0 = Instant.parse("2026-11-06T04:30:00Z");

    @Autowired LlmRateLimiter limiter;
    @Autowired StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        redisTemplate.delete(redisTemplate.keys("llm:rate:*"));
    }

    @Test
    void 분당_5회까지_허용하고_6번째는_거절한다() {
        for (int i = 0; i < 5; i++) {
            limiter.acquire(1L, T0.plusSeconds(i));
        }

        assertThatThrownBy(() -> limiter.acquire(1L, T0.plusSeconds(30)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LLM_RATE_LIMITED);
    }

    @Test
    void 다음_분이_되면_다시_허용한다() {
        for (int i = 0; i < 5; i++) {
            limiter.acquire(1L, T0);
        }

        assertThatNoException().isThrownBy(() -> limiter.acquire(1L, T0.plus(Duration.ofMinutes(1))));
    }

    @Test
    void 거절된_요청은_횟수에_세지_않는다() {
        for (int i = 0; i < 5; i++) {
            limiter.acquire(1L, T0);
        }
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> limiter.acquire(1L, T0)).isInstanceOf(BusinessException.class);
        }

        assertThat(redisTemplate.opsForValue().get(limiter.minuteKey(1L, T0))).isEqualTo("5");
        assertThat(redisTemplate.opsForValue().get(limiter.dayKey(1L, T0))).isEqualTo("5");
    }

    @Test
    void 하루_50회를_넘으면_일일_한도_코드로_거절한다() {
        for (int i = 0; i < 50; i++) {
            limiter.acquire(1L, T0.plus(Duration.ofMinutes(i)));   // 분당 1회씩이라 분 한도에는 안 걸린다
        }

        assertThatThrownBy(() -> limiter.acquire(1L, T0.plus(Duration.ofMinutes(60))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LLM_DAILY_LIMIT_EXCEEDED);
    }

    @Test
    void 일일_한도는_한국_자정에_초기화된다() {
        Instant kstEndOfDay = Instant.parse("2026-11-06T14:59:59Z");   // 23:59:59 KST
        redisTemplate.opsForValue().set(limiter.dayKey(1L, kstEndOfDay), "50");

        assertThatThrownBy(() -> limiter.acquire(1L, kstEndOfDay)).isInstanceOf(BusinessException.class);
        assertThatNoException().isThrownBy(() -> limiter.acquire(1L, kstEndOfDay.plusSeconds(1)));   // 00:00:00 KST
        assertThat(limiter.dayKey(1L, kstEndOfDay)).endsWith(":d:20261106");
        assertThat(limiter.dayKey(1L, kstEndOfDay.plusSeconds(1))).endsWith(":d:20261107");
    }

    @Test
    void 사용자마다_따로_센다() {
        for (int i = 0; i < 5; i++) {
            limiter.acquire(1L, T0);
        }

        assertThatNoException().isThrownBy(() -> limiter.acquire(2L, T0));
    }

    @Test
    void 키에는_만료가_걸린다() {
        limiter.acquire(1L, T0);

        assertThat(redisTemplate.getExpire(limiter.minuteKey(1L, T0))).isBetween(1L, 120L);
        assertThat(redisTemplate.getExpire(limiter.dayKey(1L, T0))).isBetween(1L, Duration.ofDays(2).toSeconds());
    }
}
