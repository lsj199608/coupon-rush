package com.couponrush.llmservice.processing;

import com.couponrush.llm.contract.EventCopyInput;
import com.couponrush.llm.contract.EventCopyTone;
import com.couponrush.llm.contract.EventSearchInput;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseCacheKeyTest {

    private static final String CLIENT = "anthropic:claude-sonnet-5-5:event-copy.v1:effort=low";

    private static EventCopyInput input(String keywords, EventCopyTone tone, int quantity, Instant start) {
        return new EventCopyInput(keywords, tone, quantity, start, start == null ? null : start.plusSeconds(3600));
    }

    @Test
    void 공백_대소문자_차이는_같은_키() {
        assertThat(ResponseCache.key(CLIENT, input("Winter  Sale", EventCopyTone.URGENT, 10, null)))
                .isEqualTo(ResponseCache.key(CLIENT, input(" winter sale ", EventCopyTone.URGENT, 10, null)));
    }

    @Test
    void 내용이_다르면_다른_키() {
        String base = ResponseCache.key(CLIENT, input("겨울 할인", EventCopyTone.URGENT, 10, null));

        assertThat(ResponseCache.key(CLIENT, input("겨울 할인", EventCopyTone.FRIENDLY, 10, null))).isNotEqualTo(base);
        assertThat(ResponseCache.key(CLIENT, input("겨울 할인", EventCopyTone.URGENT, 11, null))).isNotEqualTo(base);
        assertThat(ResponseCache.key(CLIENT, input("겨울 할인", EventCopyTone.URGENT, 10, Instant.parse("2030-01-01T00:00:00Z"))))
                .isNotEqualTo(base);
    }

    @Test
    void 모델이나_프롬프트_버전이_바뀌면_다른_키_예전_응답을_재사용하지_않는다() {
        EventCopyInput same = input("겨울 할인", EventCopyTone.URGENT, 10, null);

        assertThat(ResponseCache.key("anthropic:claude-haiku-5-5:event-copy.v1:effort=low", same))
                .isNotEqualTo(ResponseCache.key(CLIENT, same));
        assertThat(ResponseCache.key("anthropic:claude-sonnet-5-5:event-copy.v2:effort=low", same))
                .isNotEqualTo(ResponseCache.key(CLIENT, same));
        assertThat(ResponseCache.key("fake:v1", same)).isNotEqualTo(ResponseCache.key(CLIENT, same));
    }

    @Test
    void 검색은_같은_날_같은_질문이면_같은_키이고_날짜가_바뀌면_다른_키() {
        String search = "anthropic:claude-sonnet-5-5:event-search.v1:effort=low";
        LocalDate today = LocalDate.of(2030, 1, 2);
        String base = ResponseCache.key(search, new EventSearchInput("이번 주 끝나는  커피"), today);

        assertThat(ResponseCache.key(search, new EventSearchInput(" 이번 주 끝나는 커피 "), today)).isEqualTo(base);
        assertThat(ResponseCache.key(search, new EventSearchInput("이번 주 끝나는 커피"), today.plusDays(1)))
                .as("'이번 주'의 뜻이 날짜마다 다르다").isNotEqualTo(base);
        assertThat(ResponseCache.key(search, new EventSearchInput("이번 주 끝나는 라떼"), today)).isNotEqualTo(base);
        assertThat(ResponseCache.key("anthropic:claude-sonnet-5-5:event-search.v2:effort=low",
                new EventSearchInput("이번 주 끝나는 커피"), today)).as("프롬프트 버전").isNotEqualTo(base);
        assertThat(base).startsWith("llm:cache:").doesNotContain("커피");
    }

    @Test
    void 키에는_입력_원문이_드러나지_않는다() {
        String key = ResponseCache.key(CLIENT, input("겨울 할인", EventCopyTone.URGENT, 10, null));

        assertThat(key).startsWith("llm:cache:").doesNotContain("겨울").hasSize("llm:cache:".length() + 64);
    }
}
