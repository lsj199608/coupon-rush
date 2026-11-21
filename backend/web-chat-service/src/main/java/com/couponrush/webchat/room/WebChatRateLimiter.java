package com.couponrush.webchat.room;

import com.couponrush.webchat.config.WebChatProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 회원별 도배 제한 — 최근 window 동안 보낸 시각을 기억하는 미끄러지는 창 (회원당 최대 maxMessages 개라 메모리가 작다).
 * <ul>
 *   <li>세션이 아니라 회원 기준: 탭을 여러 개 열어 한도를 늘리지 못하게</li>
 *   <li>한도로 거절된 시도는 세지 않는다 (한도에 걸린 사람이 계속 눌러도 풀리는 시각이 밀리지 않게).
 *       길이 초과처럼 내용으로 거절된 메시지는 센다 — 컨트롤러가 내용 검사보다 한도를 먼저 본다</li>
 *   <li>회원마다 {@link ConcurrentMap#compute} 로 원자적으로 판정 — 같은 회원의 동시 메시지도 한도를 넘지 못한다</li>
 * </ul>
 * 한계: 서버 메모리라 서버마다 따로 센다 (D1 = 1대 기준). 여러 대(D3)에서 한 회원이 두 서버에 붙으면 한도가 대수만큼 늘어난다
 * — 필요하면 Redis 카운터(coupon-api 레이트 리밋 Lua 와 같은 방식)로 옮긴다.
 */
@Component
public class WebChatRateLimiter {

    private final ConcurrentMap<Long, Deque<Instant>> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxMessages;
    private final Duration window;

    public WebChatRateLimiter(Clock clock, WebChatProperties properties) {
        this.clock = clock;
        this.maxMessages = properties.rateLimit().maxMessages();
        this.window = properties.rateLimit().window();
    }

    /** 보낼 수 있으면 1회 세고 true. */
    public boolean tryAcquire(long userId) {
        Instant now = clock.instant();
        boolean[] allowed = {false};
        windows.compute(userId, (id, sent) -> {
            Deque<Instant> times = sent == null ? new ArrayDeque<>(maxMessages) : sent;
            dropExpired(times, now);
            if (times.size() < maxMessages) {
                times.addLast(now);
                allowed[0] = true;
            }
            return times;
        });
        return allowed[0];
    }

    /** 한동안 말이 없는 회원의 기록을 지운다 (나간 회원이 메모리에 계속 남지 않게). */
    @Scheduled(fixedDelayString = "${app.web-chat.rate-limit.sweep-interval:60s}")
    public void sweep() {
        Instant now = clock.instant();
        for (Long userId : windows.keySet()) {
            windows.computeIfPresent(userId, (id, times) -> {
                dropExpired(times, now);
                return times.isEmpty() ? null : times;
            });
        }
    }

    int trackedUsers() {
        return windows.size();
    }

    private void dropExpired(Deque<Instant> times, Instant now) {
        Instant oldestAllowed = now.minus(window);
        while (!times.isEmpty() && !times.peekFirst().isAfter(oldestAllowed)) {
            times.pollFirst();
        }
    }
}
