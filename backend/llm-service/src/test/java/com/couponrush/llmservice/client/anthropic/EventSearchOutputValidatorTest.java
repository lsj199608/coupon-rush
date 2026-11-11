package com.couponrush.llmservice.client.anthropic;

import com.couponrush.llm.contract.EventSearchFilter;
import com.couponrush.llm.contract.EventSearchStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventSearchOutputValidatorTest {

    private static EventSearchOutput output(List<String> keywords, String endFrom, String endTo) {
        return new EventSearchOutput(keywords, List.of(EventSearchStatus.OPEN), "", "", endFrom, endTo, true);
    }

    @Test
    void 양끝_포함_날짜를_한국_시각_반열린_범위로_바꾼다() {
        EventSearchOutputValidator.Result result = EventSearchOutputValidator.validate(
                output(List.of("커피"), "2029-12-31", "2030-01-06"));

        assertThat(result.valid()).isTrue();
        EventSearchFilter filter = result.filter();
        assertThat(filter.endFrom()).isEqualTo(Instant.parse("2029-12-30T15:00:00Z"));   // 12월 31일 0시 KST
        assertThat(filter.endTo()).as("1월 6일 포함 → 1월 7일 0시 미포함").isEqualTo(Instant.parse("2030-01-06T15:00:00Z"));
        assertThat(filter.startFrom()).as("빈 문자열 = 조건 없음").isNull();
        assertThat(filter.statuses()).containsExactly(EventSearchStatus.OPEN);
        assertThat(filter.availableOnly()).isTrue();
    }

    @Test
    void 하루짜리_범위와_한쪽만_있는_범위도_된다() {
        EventSearchFilter oneDay = EventSearchOutputValidator.validate(output(List.of(), "2030-01-03", "2030-01-03")).filter();
        assertThat(oneDay.endFrom()).isEqualTo(Instant.parse("2030-01-02T15:00:00Z"));
        assertThat(oneDay.endTo()).isEqualTo(Instant.parse("2030-01-03T15:00:00Z"));

        EventSearchFilter openEnded = EventSearchOutputValidator.validate(output(List.of(), "", "2030-01-03")).filter();
        assertThat(openEnded.endFrom()).isNull();
        assertThat(openEnded.endTo()).isNotNull();
    }

    @Test
    void 단어의_공백_빈_값_중복은_고쳐서_받는다() {
        java.util.ArrayList<String> keywords = new java.util.ArrayList<>(List.of(" 커피 ", "커피", "  ", "라떼"));
        keywords.add(null);

        EventSearchFilter filter = EventSearchOutputValidator.validate(output(keywords, "", "")).filter();

        assertThat(filter.keywords()).containsExactly("커피", "라떼");
    }

    @Test
    void 고칠_수_없는_출력은_사유와_함께_거절한다_재생성_대상() {
        assertThat(EventSearchOutputValidator.validate(null).problem()).isNotNull();
        assertThat(EventSearchOutputValidator.validate(output(List.of("a", "b", "c", "d", "e", "f"), "", "")).problem())
                .contains("단어 6개");
        assertThat(EventSearchOutputValidator.validate(output(List.of("가".repeat(51)), "", "")).problem())
                .contains("단어 길이 51자");
        assertThat(EventSearchOutputValidator.validate(output(List.of(), "2030-01-06", "2029-12-31")).problem())
                .contains("뒤집힘");
        assertThat(EventSearchOutputValidator.validate(output(List.of(), "다음 주", "")).problem())
                .contains("날짜 형식");
    }
}
