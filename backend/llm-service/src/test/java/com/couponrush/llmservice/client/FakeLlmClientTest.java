package com.couponrush.llmservice.client;

import com.couponrush.llm.contract.EventCopyCandidate;
import com.couponrush.llm.contract.EventCopyInput;
import com.couponrush.llm.contract.EventCopyTone;
import com.couponrush.llm.contract.LlmResultMessage;
import com.couponrush.llmservice.LlmServiceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FakeLlmClientTest {

    private final FakeLlmClient client = new FakeLlmClient(
            new LlmServiceProperties(Duration.ofDays(1), Duration.ofSeconds(10), new LlmServiceProperties.Fake(Duration.ZERO)));

    private static EventCopyInput input(EventCopyTone tone, String keywords) {
        return new EventCopyInput(keywords, tone, 300,
                Instant.parse("2030-01-02T01:00:00Z"), Instant.parse("2030-01-02T03:00:00Z"));
    }

    @ParameterizedTest
    @EnumSource(EventCopyTone.class)
    void 톤마다_후보_3개를_만들고_계약_길이를_지킨다(EventCopyTone tone) {
        LlmCompletion completion = client.generateEventCopy(input(tone, "겨울 한정 핫초코"));

        assertThat(completion.candidates()).hasSize(LlmResultMessage.CANDIDATE_COUNT);
        assertThat(completion.model()).isEqualTo("fake");
        assertThat(completion.inputTokens()).isPositive();
        assertThat(completion.outputTokens()).isPositive();
        assertThat(completion.candidates()).allSatisfy(c -> assertThat(c.name()).contains("핫초코").hasSizeLessThanOrEqualTo(100));
    }

    @Test
    void 같은_입력이면_항상_같은_결과다() {
        assertThat(client.generateEventCopy(input(EventCopyTone.URGENT, "봄")))
                .isEqualTo(client.generateEventCopy(input(EventCopyTone.URGENT, "봄")));
    }

    @Test
    void 수량과_발급_기간을_한국_시각으로_문구에_넣는다() {
        EventCopyCandidate first = client.generateEventCopy(input(EventCopyTone.URGENT, "핫초코")).candidates().get(0);

        assertThat(first.name()).contains("선착순 300명");
        assertThat(first.description()).contains("1월 2일 10:00 ~ 1월 2일 12:00");   // 01:00Z = 10:00 KST
    }

    @Test
    void 키워드가_최대_길이여도_후보는_계약_길이를_넘지_않는다() {
        LlmCompletion completion = client.generateEventCopy(input(EventCopyTone.PREMIUM, "가".repeat(EventCopyInput.KEYWORDS_MAX)));

        assertThat(completion.candidates()).allSatisfy(c -> {
            assertThat(c.name()).hasSizeLessThanOrEqualTo(EventCopyCandidate.NAME_MAX);
            assertThat(c.description()).hasSizeLessThanOrEqualTo(EventCopyCandidate.DESCRIPTION_MAX);
        });
    }

    @Test
    void 기간이_없으면_기간_문구를_넣지_않는다() {
        LlmCompletion completion = client.generateEventCopy(
                new EventCopyInput("봄맞이", EventCopyTone.FRIENDLY, 10, null, null));

        assertThat(completion.candidates()).allSatisfy(c -> assertThat(c.description()).doesNotContain("~"));
    }
}
