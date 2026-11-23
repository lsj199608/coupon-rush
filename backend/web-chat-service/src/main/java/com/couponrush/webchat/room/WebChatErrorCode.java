package com.couponrush.webchat.room;

/**
 * 채팅방 거절 사유. 접두사 WEB_CHAT_ 은 챗봇(coupon-api CHATBOT_*) 코드와 프론트 메시지 표에서 섞이지 않게.
 * <p>
 * 전달 방식이 세 가지다:
 * <ul>
 *   <li>프로토콜 위반(연결 끊김) — STOMP ERROR 프레임의 message 헤더. 정상 화면에서는 일어나지 않는 요청</li>
 *   <li>보낸 메시지 거절(연결 유지) — /user/queue/errors 로 보낸 {@link WebChatError}</li>
 *   <li>지난 대화 조회(HTTP, D6) — 응답 본문 {@link WebChatError}</li>
 * </ul>
 */
public enum WebChatErrorCode {

    // ── ERROR 프레임 (연결 끊김) ──
    /** 허용하지 않은 주소에 구독·보내기 (/topic 직접 SEND, 다른 주제, 잘못된 이벤트 ID). */
    WEB_CHAT_FORBIDDEN_DESTINATION("사용할 수 없는 채팅 주소입니다."),
    /** 비로그인 연결의 보내기. */
    WEB_CHAT_LOGIN_REQUIRED("로그인해야 메시지를 보낼 수 있습니다."),
    /** 연결 인증 실패 (D2 티켓 만료·재사용 등). */
    WEB_CHAT_UNAUTHORIZED("채팅 연결 인증에 실패했습니다."),
    /** 그 밖의 처리 오류 (내부 사정은 클라이언트에 알리지 않는다). */
    WEB_CHAT_ERROR("채팅 처리 중 오류가 발생했습니다."),

    // ── /user/queue/errors (연결 유지) ──
    WEB_CHAT_INVALID_MESSAGE("메시지 형식이 올바르지 않습니다."),
    WEB_CHAT_EMPTY_MESSAGE("메시지를 입력해 주세요."),
    WEB_CHAT_MESSAGE_TOO_LONG("메시지는 " + WebChatMessage.CONTENT_MAX + "자까지 보낼 수 있습니다."),
    WEB_CHAT_RATE_LIMITED("메시지를 너무 빨리 보내고 있습니다. 잠시 후 다시 보내 주세요."),
    /** Redis 장애(도배 제한 확인·서버 간 전달)로 보내지 못함 (D3) — 아무에게도 가지 않았고 저장하지 않았다. 다시 보내면 된다. */
    WEB_CHAT_UNAVAILABLE("메시지를 보내지 못했습니다. 잠시 후 다시 보내 주세요."),

    // ── HTTP 응답 {"code","message"} (지난 대화 조회, D6) ──
    /** 이벤트 ID·beforeId·size 가 올바르지 않음 (400). */
    WEB_CHAT_INVALID_REQUEST("요청 값이 올바르지 않습니다."),
    /** DB 장애로 지난 대화를 읽지 못함 (503) — 실시간 대화는 계속된다. */
    WEB_CHAT_HISTORY_UNAVAILABLE("지난 대화를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final String message;

    WebChatErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
