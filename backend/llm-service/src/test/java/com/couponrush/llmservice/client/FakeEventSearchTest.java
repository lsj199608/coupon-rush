package com.couponrush.llmservice.client;

import com.couponrush.llm.contract.EventSearchFilter;
import com.couponrush.llm.contract.EventSearchInput;
import com.couponrush.llm.contract.EventSearchStatus;
import com.couponrush.llm.contract.LlmRequestType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** fake 제공자의 규칙 기반 검색 조건. 기준일 2030-01-02(수) — 그 주 월요일은 2029-12-31 */
class FakeEventSearchTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 1, 2);

    private final FakeLlmClient client = new FakeLlmClient(TestProperties.withAnthropic(TestProperties.anthropic("unused")));

    private EventSearchFilter search(String query) {
        return client.extractSearchFilter(new EventSearchInput(query), TODAY).filter();
    }

    @Test
    void 이번_주에_끝나는_커피_쿠폰() {
        EventSearchFilter filter = search("이번 주에 끝나는 커피 쿠폰");

        assertThat(filter.keywords()).containsExactly("커피");   // "에" 조사, "쿠폰" 일반어는 뺀다
        assertThat(filter.endFrom()).as("월요일 0시 KST").isEqualTo(Instant.parse("2029-12-30T15:00:00Z"));
        assertThat(filter.endTo()).as("다음 주 월요일 0시 KST (미포함)").isEqualTo(Instant.parse("2030-01-06T15:00:00Z"));
        assertThat(filter.startFrom()).isNull();
        assertThat(filter.statuses()).isEmpty();
        assertThat(filter.availableOnly()).isFalse();
    }

    @Test
    void 진행_중이고_남은_치킨_이벤트() {
        EventSearchFilter filter = search("진행 중인 치킨 이벤트 남은 거");

        assertThat(filter.statuses()).containsExactly(EventSearchStatus.OPEN);
        assertThat(filter.availableOnly()).isTrue();
        assertThat(filter.keywords()).containsExactly("치킨");
    }

    @Test
    void 시작을_말하면_발급_시작_범위로_간다() {
        EventSearchFilter filter = search("내일 시작하는 이벤트");

        assertThat(filter.startFrom()).isEqualTo(Instant.parse("2030-01-02T15:00:00Z"));   // 1월 3일 0시 KST
        assertThat(filter.startTo()).isEqualTo(Instant.parse("2030-01-03T15:00:00Z"));
        assertThat(filter.endFrom()).isNull();
        assertThat(filter.keywords()).isEmpty();
    }

    @Test
    void 종료된_것과_오픈_예정은_상태로() {
        assertThat(search("종료된 피자 쿠폰").statuses()).containsExactly(EventSearchStatus.ENDED);
        assertThat(search("오픈 예정 이벤트").statuses()).containsExactly(EventSearchStatus.UPCOMING);
        assertThat(search("끝나는 피자 쿠폰").statuses()).as("'끝나는'은 종료 상태가 아니라 기간 표현").isEmpty();
    }

    @Test
    void 일반어만_있으면_조건이_비어_있다() {
        assertThat(search("쿠폰 보여줘").isEmpty()).isTrue();
    }

    @Test
    void 긴_질문도_계약_범위를_넘지_않는다() {
        EventSearchFilter filter = search("가".repeat(60) + " 나나 다다 라라 마마 바바 사사 아아");

        assertThat(filter.keywords()).hasSize(EventSearchFilter.KEYWORD_COUNT_MAX);
        assertThat(filter.keywords()).allSatisfy(k -> assertThat(k).hasSizeLessThanOrEqualTo(EventSearchFilter.KEYWORD_MAX));
    }

    @Test
    void 같은_날_같은_질문이면_같은_조건이고_날짜가_바뀌면_기간도_바뀐다() {
        assertThat(search("이번 주 끝나는 커피")).isEqualTo(search("이번 주 끝나는 커피"));

        EventSearchFilter nextWeek = client.extractSearchFilter(new EventSearchInput("이번 주 끝나는 커피"), TODAY.plusWeeks(1)).filter();
        assertThat(nextWeek.endFrom()).isEqualTo(Instant.parse("2030-01-06T15:00:00Z"));
    }

    @Test
    void 토큰과_모델을_보고하고_기능별로_캐시_식별자가_다르다() {
        LlmSearchCompletion completion = client.extractSearchFilter(new EventSearchInput("커피"), TODAY);

        assertThat(completion.model()).isEqualTo("fake");
        assertThat(completion.inputTokens()).isPositive();
        assertThat(client.cacheIdentity(LlmRequestType.EVENT_COPY)).as("기존 문구 캐시 유지").isEqualTo("fake:v1");
        assertThat(client.cacheIdentity(LlmRequestType.EVENT_SEARCH)).isNotEqualTo(client.cacheIdentity(LlmRequestType.EVENT_COPY));
    }
}
