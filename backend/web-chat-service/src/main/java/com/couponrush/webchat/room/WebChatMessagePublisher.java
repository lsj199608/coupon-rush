package com.couponrush.webchat.room;

/**
 * 검사를 마친 메시지를 방 구독자에게 전달한다.
 * D1 은 이 서버의 구독자에게만({@link LocalWebChatMessagePublisher}). D3 에서 Redis pub/sub 구현으로 바꾸면
 * 다른 서버에 붙은 구독자에게도 간다 — 컨트롤러는 바뀌지 않게 경계를 여기에 둔다.
 */
public interface WebChatMessagePublisher {

    void publish(WebChatMessage message);
}
