package com.couponrush.webchat.room;

/** /user/queue/errors 로 보내는 거절 응답 (coupon-api 의 {"code","message"} 와 같은 모양). */
public record WebChatError(String code, String message) {

    public static WebChatError of(WebChatErrorCode code) {
        return new WebChatError(code.name(), code.message());
    }
}
