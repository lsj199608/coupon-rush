package com.couponrush.webchat.room;

import com.couponrush.webchat.config.WebChatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * 회원별 도배 제한 — 최근 window 동안 보낸 시각을 Redis ZSET 에 두는 미끄러지는 창 (D3 에서 서버 메모리 → Redis).
 * <ul>
 *   <li><b>모든 서버가 한 장부를 본다</b>: 서버 메모리로 세면 서버마다 따로 세어, 한 회원이 탭(연결)을 여러 서버에 붙이면 한도가 대수만큼 늘고
 *       다른 서버로 다시 연결하면 0부터 시작했다</li>
 *   <li>세션이 아니라 회원 기준: 탭을 여러 개 열어 한도를 늘리지 못하게</li>
 *   <li>한도로 거절된 시도는 세지 않는다 (계속 눌러도 풀리는 시각이 밀리지 않게).
 *       길이 초과처럼 내용으로 거절된 메시지는 센다 — 서비스가 내용 검사보다 한도를 먼저 본다</li>
 *   <li>판정은 Lua 하나로 (정리 → 개수 → 추가가 원자적) — 같은 회원의 동시 메시지가 어느 서버로 와도 한도를 넘지 못한다</li>
 * </ul>
 * 시각은 앱 Clock 값을 넘긴다 (Redis TIME 이 아니라): 테스트에서 시간을 고정할 수 있고, 서버 간 시계 차이(NTP 로 수 ms)는 10초 창에 비해 무시할 만하다.
 * 키는 창 길이만큼 지나면 사라진다 (PEXPIRE) — 예전의 정리 스케줄러가 필요 없다.
 * <p>
 * Redis 장애면 보내기를 거절한다({@link WebChatErrorCode#WEB_CHAT_UNAVAILABLE}). 한도 없이 통과시켜도 이어지는 발행이 같은 Redis 라 어차피 실패한다.
 */
@Component
public class WebChatRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(WebChatRateLimiter.class);
    private static final String KEY_PREFIX = "web-chat:rate:";
    private static final DefaultRedisScript<Long> SCRIPT = loadScript();

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final int maxMessages;
    private final long windowMillis;

    public WebChatRateLimiter(StringRedisTemplate redisTemplate, Clock clock, WebChatProperties properties) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.maxMessages = properties.rateLimit().maxMessages();
        this.windowMillis = properties.rateLimit().window().toMillis();
    }

    /**
     * 보낼 수 있으면 1회 세고 true.
     *
     * @throws WebChatRejectedException Redis 장애 ({@link WebChatErrorCode#WEB_CHAT_UNAVAILABLE})
     */
    public boolean tryAcquire(long userId) {
        try {
            Long allowed = redisTemplate.execute(SCRIPT, List.of(key(userId)),
                    String.valueOf(clock.millis()), String.valueOf(windowMillis), String.valueOf(maxMessages),
                    UUID.randomUUID().toString()); // 같은 ms 에 두 번 보내도 ZSET 의 다른 기록이 되게
            return allowed != null && allowed == 1L;
        } catch (DataAccessException e) {
            log.warn("도배 제한 확인 실패 (Redis) userId={}: {}", userId, e.getMessage());
            throw new WebChatRejectedException(WebChatErrorCode.WEB_CHAT_UNAVAILABLE);
        }
    }

    static String key(long userId) {
        return KEY_PREFIX + userId;
    }

    /** 기동 시 문자열로 한 번 읽는다 (ResourceScriptSource 는 호출마다 파일 확인 → 직렬화. CLAUDE.md 4단계 메모) */
    private static DefaultRedisScript<Long> loadScript() {
        try {
            String source = StreamUtils.copyToString(
                    new ClassPathResource("redis/web-chat-rate-limit.lua").getInputStream(), StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(source, Long.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
