package com.couponrush.webchat.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 채팅 연결 티켓 규칙.
 * <pre>
 * coupon-api      : POST /api/web-chat/tickets → 티켓 생성 → SET {@link #redisKey}(티켓) = {@link WebChatTicket} JSON, EX {@link #TTL}
 * web-chat-service: STOMP CONNECT 의 {@value #CONNECT_HEADER} 헤더 → GETDEL {@link #redisKey}(티켓) → 있으면 그 회원으로 연결
 * </pre>
 * <ul>
 *   <li>1회용: 꺼내면서 지운다(GETDEL, 원자적) — 같은 티켓으로 동시에 두 번 연결해도 하나만 성공</li>
 *   <li>키에는 티켓 원문이 아니라 SHA-256 해시 — Redis 내용이 새도 바로 쓸 수 있는 티켓이 없다 (refresh token 과 같은 방식)</li>
 *   <li>짧은 TTL: 티켓을 받고 바로 연결하는 용도라 30초면 충분하고, 새어도 금방 쓸모없어진다</li>
 * </ul>
 */
public final class WebChatTickets {

    /** 티켓 유효 시간 */
    public static final Duration TTL = Duration.ofSeconds(30);

    /** STOMP CONNECT 프레임에서 티켓을 싣는 헤더 이름 (브라우저 WebSocket 은 Authorization 헤더를 못 붙이고, URL 에는 싣지 않는 규칙) */
    public static final String CONNECT_HEADER = "ticket";

    /** 무작위 바이트 수 — base64url(패딩 없음)로 43자 */
    public static final int RANDOM_BYTES = 32;

    /** 티켓 모양: base64url 43자. 다른 모양은 Redis 를 보기 전에 거절 (긴 헤더로 해시 계산을 시키지 못하게) */
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final String KEY_PREFIX = "web-chat:ticket:";

    private WebChatTickets() {
    }

    public static boolean isWellFormed(String ticket) {
        return ticket != null && FORMAT.matcher(ticket).matches();
    }

    /** Redis 키 = web-chat:ticket:{SHA-256(티켓) 16진수} */
    public static String redisKey(String ticket) {
        if (!isWellFormed(ticket)) {
            throw new IllegalArgumentException("티켓 형식이 아닙니다");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.US_ASCII));
            return KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e); // 모든 JVM 이 제공해야 하는 알고리즘
        }
    }
}
