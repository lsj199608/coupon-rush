package com.couponrush.webchat.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebChatTicketsTest {

    private static final String TICKET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123-_6"; // 43자

    /** 두 서비스의 Boot ObjectMapper 와 같은 설정 (시각은 ISO 문자열) + 모르는 필드에 엄격 — ignoreUnknown 이 실제로 동작하는지 */
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void 키는_티켓_원문이_아니라_SHA256_해시다() {
        String key = WebChatTickets.redisKey(TICKET);

        assertThat(key).startsWith("web-chat:ticket:").doesNotContain(TICKET);
        assertThat(key.substring("web-chat:ticket:".length())).matches("[0-9a-f]{64}");
        assertThat(WebChatTickets.redisKey(TICKET)).as("같은 티켓이면 같은 키 (두 서비스가 같은 키를 계산)").isEqualTo(key);
        assertThat(WebChatTickets.redisKey(TICKET.replace('6', '7'))).isNotEqualTo(key);
    }

    @Test
    void 키_규칙을_고정한다_바꾸면_먼저_배포된_쪽이_티켓을_못_찾는다() {
        // printf '%s' "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123-_6" | sha256sum  (밖에서 계산한 값)
        assertThat(WebChatTickets.redisKey(TICKET))
                .isEqualTo("web-chat:ticket:e7fa42a75203cd2dafc0e61abdf9bbc1f783bc4f960c41e5b551eebf7ec6a1f8");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123-_67", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123+/6",
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123-_=", "가bcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123-_6"})
    void 형식이_아닌_티켓은_키를_만들지_않는다(String ticket) {
        assertThat(WebChatTickets.isWellFormed(ticket)).isFalse();
        assertThatThrownBy(() -> WebChatTickets.redisKey(ticket)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 무작위_32바이트를_base64url_로_쓰면_형식에_맞는다() {
        byte[] bytes = new byte[WebChatTickets.RANDOM_BYTES];
        java.util.Arrays.fill(bytes, (byte) 0xFB); // +/ 가 나오는 값 — base64url 에서는 -_
        String ticket = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        assertThat(WebChatTickets.isWellFormed(ticket)).isTrue();
    }

    @Test
    void 짧은_TTL_과_헤더_이름() {
        assertThat(WebChatTickets.TTL).isEqualTo(Duration.ofSeconds(30));
        assertThat(WebChatTickets.CONNECT_HEADER).isEqualTo("ticket");
    }

    @Test
    void 티켓_내용_JSON_은_필드_이름이_고정이고_모르는_필드를_무시한다() throws Exception {
        WebChatTicket original = new WebChatTicket(7, "회원", Instant.parse("2030-01-01T00:00:00Z"));

        String json = mapper.writeValueAsString(original);
        JsonNode node = mapper.readTree(json);
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);

        assertThat(names).containsExactlyInAnyOrder("userId", "nickname", "issuedAt");
        assertThat(mapper.readValue(json, WebChatTicket.class)).isEqualTo(original);
        assertThat(mapper.readValue("""
                {"userId":7,"nickname":"회원","issuedAt":"2030-01-01T00:00:00Z","eventId":3}
                """, WebChatTicket.class)).isEqualTo(original);
    }

    @Test
    void 잘못된_티켓_내용은_만들_수_없다() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> new WebChatTicket(0, "회원", now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebChatTicket(1, " ", now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebChatTicket(1, "가".repeat(51), now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebChatTicket(1, "회원", null)).isInstanceOf(NullPointerException.class);
    }
}
