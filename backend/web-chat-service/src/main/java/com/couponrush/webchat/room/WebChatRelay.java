package com.couponrush.webchat.room;

/**
 * 서버 간 전달(D3) 규칙.
 * <p>
 * <b>채널 하나에 모든 방</b>: 메시지 본문에 eventId 가 있어 받는 서버가 방을 고른다.
 * 방마다 채널(web-chat:room:{id})을 두고 구독자가 있는 방만 SUBSCRIBE 하면 서버가 안 보는 방의 메시지를 받지 않지만,
 * 구독·해지·연결 끊김마다 방별 인원 수를 세야 해서 복잡하다. 서버 몇 대 규모에선 모든 서버가 모든 메시지를 받아도 부담이 작다
 * — 서버가 많아져 받는 양이 문제가 되면 그때 바꾼다.
 * <p>
 * Redis pub/sub 은 저장하지 않는다(at-most-once): 구독 연결이 끊긴 동안의 메시지는 그 서버의 접속자에게 가지 않는다.
 * 저장(D6)은 보낸 서버가 하므로 지난 대화에는 남는다 — 화면은 재연결 때 지난 대화를 다시 불러 메꾼다(D4).
 */
public final class WebChatRelay {

    /** 이 서비스만 쓰는 채널 (다른 서비스와 공유하지 않으므로 계약 모듈에 두지 않는다). */
    public static final String CHANNEL = "web-chat:messages";

    private WebChatRelay() {
    }
}
