package com.couponrush.webchat.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 발행 모양과 Redis 장애 처리 (실제 서버 간 전달은 WebChatScaleOutTest). */
class RedisWebChatMessagePublisherTest {

    private static final WebChatMessage MESSAGE = new WebChatMessage("00000000-0000-0000-0000-000000000001", 11, 7, "회원7",
            "안녕", Instant.parse("2026-11-23T03:00:00.123456Z"));

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // Boot 기본과 같게
    private final RedisWebChatMessagePublisher publisher = new RedisWebChatMessagePublisher(redisTemplate, objectMapper);

    @Test
    void 메시지를_JSON_으로_채널에_보내고_받는_쪽이_같은_값으로_읽는다() throws Exception {
        publisher.publish(MESSAGE);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq(WebChatRelay.CHANNEL), json.capture());
        assertThat(objectMapper.readValue(json.getValue(), WebChatMessage.class)).isEqualTo(MESSAGE);
    }

    @Test
    void Redis_장애는_보낸_사람에게_알릴_거절로_바꾼다() {
        when(redisTemplate.convertAndSend(eq(WebChatRelay.CHANNEL), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> publisher.publish(MESSAGE))
                .isInstanceOfSatisfying(WebChatRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo(WebChatErrorCode.WEB_CHAT_UNAVAILABLE));
    }
}
