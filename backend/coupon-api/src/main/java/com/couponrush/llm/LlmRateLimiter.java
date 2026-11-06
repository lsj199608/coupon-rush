package com.couponrush.llm;

import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 사용자별 LLM 요청 한도 (기본 분당 5회 / 일 50회). coupon-api 에서 확인한다 —
 * 넘으면 행도 메시지도 만들지 않고 바로 429 (발급 경로의 Lua 판정과 같은 발상: 거절은 가장 앞에서 싸게).
 * <p>
 * 키: llm:rate:{userId}:d:{yyyyMMdd}, llm:rate:{userId}:m:{yyyyMMddHHmm} (app.llm.zone 기준 시각).
 * 중괄호는 Redis Cluster hash tag — 두 키가 같은 슬롯이라 Lua 하나에서 함께 다룰 수 있다.
 */
@Component
public class LlmRateLimiter {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final Duration DAY_KEY_TTL = Duration.ofDays(2);       // 창보다 넉넉히 (시계 차이 대비)
    private static final Duration MINUTE_KEY_TTL = Duration.ofMinutes(2);

    private static final DefaultRedisScript<Long> SCRIPT = loadScript();

    private final StringRedisTemplate redisTemplate;
    private final LlmProperties properties;

    public LlmRateLimiter(StringRedisTemplate redisTemplate, LlmProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /** 한도 안이면 1회 사용으로 기록하고 반환, 넘었으면 429 BusinessException */
    public void acquire(Long userId, Instant now) {
        Long result = redisTemplate.execute(SCRIPT, List.of(dayKey(userId, now), minuteKey(userId, now)),
                String.valueOf(properties.rateLimit().perDay()), String.valueOf(properties.rateLimit().perMinute()),
                String.valueOf(DAY_KEY_TTL.toSeconds()), String.valueOf(MINUTE_KEY_TTL.toSeconds()));
        if (result == null) {
            throw new IllegalStateException("레이트 리밋 스크립트 결과 없음");
        }
        if (result == 2L) {
            throw new BusinessException(ErrorCode.LLM_DAILY_LIMIT_EXCEEDED);
        }
        if (result == 1L) {
            throw new BusinessException(ErrorCode.LLM_RATE_LIMITED);
        }
    }

    public String dayKey(Long userId, Instant now) {
        return "llm:rate:{" + userId + "}:d:" + DAY.format(zoned(now));
    }

    public String minuteKey(Long userId, Instant now) {
        return "llm:rate:{" + userId + "}:m:" + MINUTE.format(zoned(now));
    }

    private ZonedDateTime zoned(Instant now) {
        return now.atZone(properties.zone());
    }

    /** 기동 시 문자열로 한 번 읽는다 (ResourceScriptSource 는 호출마다 파일 확인 → 직렬화. CLAUDE.md 4단계 메모) */
    private static DefaultRedisScript<Long> loadScript() {
        try {
            String source = StreamUtils.copyToString(
                    new ClassPathResource("redis/llm-rate-limit.lua").getInputStream(), StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(source, Long.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
