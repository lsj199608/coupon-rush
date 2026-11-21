package com.couponrush.webchat.room;

/** 채팅 요청 거절. 사유 코드만 담고, 메시지는 코드의 고정 문구라 내부 정보가 새지 않는다. */
public class WebChatRejectedException extends RuntimeException {

    private final WebChatErrorCode code;

    public WebChatRejectedException(WebChatErrorCode code) {
        super(code.message());
        this.code = code;
    }

    public WebChatErrorCode code() {
        return code;
    }
}
