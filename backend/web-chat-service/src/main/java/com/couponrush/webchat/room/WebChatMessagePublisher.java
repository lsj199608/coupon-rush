package com.couponrush.webchat.room;

/**
 * 검사를 마친 메시지를 방 구독자에게 전달한다 — 서비스({@link WebChatService})가 전달 방식을 모르게 하는 경계.
 * D1 은 이 서버 브로커로 바로 보냈고, D3 부터는 {@link RedisWebChatMessagePublisher}(Redis pub/sub)로 모든 서버의 구독자에게 간다.
 */
public interface WebChatMessagePublisher {

    /** @throws WebChatRejectedException 전달할 수 없음 ({@link WebChatErrorCode#WEB_CHAT_UNAVAILABLE}) */
    void publish(WebChatMessage message);
}
