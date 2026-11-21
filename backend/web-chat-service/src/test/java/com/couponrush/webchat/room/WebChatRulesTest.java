package com.couponrush.webchat.room;

import com.couponrush.webchat.auth.WebChatUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 주소·회원 규칙 (서버 없이). */
class WebChatRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"/topic/events/1", "/topic/events/999999999999999999", "/user/queue/errors"})
    void 구독할_수_있는_주소(String destination) {
        assertThat(WebChatDestinations.isSubscribable(destination)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/events/0", "/topic/events/01", "/topic/events/-1", "/topic/events/abc",
            "/topic/events/1000000000000000000", "/topic/events/1/x", "/topic/events/", "/topic/other",
            "/queue/errors", "/user/queue/other", "/user/1/queue/errors", "/app/events/1/chat", ""})
    void 구독할_수_없는_주소(String destination) {
        assertThat(WebChatDestinations.isSubscribable(destination)).isFalse();
        assertThat(WebChatDestinations.isSubscribable(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/events/1", "/app/events/0/chat", "/app/events/1", "/app/events/1/chat/x",
            "/app/other", "/user/queue/errors", "/queue/errors", ""})
    void 보낼_수_없는_주소(String destination) {
        assertThat(WebChatDestinations.isSendable(destination)).isFalse();
        assertThat(WebChatDestinations.isSendable("/app/events/12/chat")).isTrue();
    }

    @Test
    void 회원은_ID와_닉네임이_있어야_한다() {
        assertThat(new WebChatUser(3, "닉네임").getName()).isEqualTo("3");
        assertThatThrownBy(() -> new WebChatUser(0, "닉네임")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebChatUser(1, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebChatUser(1, "가".repeat(WebChatUser.NICKNAME_MAX + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
