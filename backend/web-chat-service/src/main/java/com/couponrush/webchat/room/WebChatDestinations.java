package com.couponrush.webchat.room;

import java.util.regex.Pattern;

/**
 * 클라이언트가 쓸 수 있는 STOMP 주소. 이 목록 밖은 {@link com.couponrush.webchat.auth.StompInboundGuard} 가 거절한다.
 * <p>
 * 특히 단순 브로커는 /topic 으로 온 SEND 를 그대로 구독자에게 뿌리므로, 클라이언트가 /topic 에 직접 보내면
 * 컨트롤러(보낸 사람 채우기·길이·도배 검사)를 건너뛰게 된다 → SEND 는 /app/events/{id}/chat 만 허용한다.
 */
public final class WebChatDestinations {

    /** 방 이름에 쓰는 이벤트 ID: 1 이상, long 범위 안 (18자리까지 — 19자리는 long 을 넘을 수 있음). */
    private static final String EVENT_ID = "[1-9][0-9]{0,17}";

    private static final Pattern ROOM = Pattern.compile("/topic/events/" + EVENT_ID);
    private static final Pattern SEND = Pattern.compile("/app/events/" + EVENT_ID + "/chat");

    /** 거절 사유를 받는 주소 (클라이언트는 /user 를 붙여 구독, 서버는 회원·세션별로 보낸다). */
    public static final String ERRORS = "/queue/errors";
    public static final String USER_ERRORS = "/user" + ERRORS;

    private WebChatDestinations() {
    }

    public static String room(long eventId) {
        return "/topic/events/" + eventId;
    }

    public static boolean isSubscribable(String destination) {
        return destination != null && (ROOM.matcher(destination).matches() || USER_ERRORS.equals(destination));
    }

    public static boolean isSendable(String destination) {
        return destination != null && SEND.matcher(destination).matches();
    }
}
