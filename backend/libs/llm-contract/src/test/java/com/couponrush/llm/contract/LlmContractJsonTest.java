package com.couponrush.llm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 두 서비스가 같은 JSON 을 주고받는지 검증한다.
 * <p>
 * ObjectMapper 는 Spring Boot 기본과 같은 핵심 설정(JavaTimeModule, 날짜를 ISO 문자열로)만 켜고,
 * FAIL_ON_UNKNOWN_PROPERTIES 는 Jackson 기본값(true, 엄격)으로 둔다 — Boot 는 이 값을 false 로 바꾸지만,
 * 계약 레코드의 @JsonIgnoreProperties 만으로 "모르는 필드 무시"가 되는지를 보기 위해 일부러 엄격하게 둔다.
 */
class LlmContractJsonTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static final UUID REQUEST_ID = UUID.fromString("0f8e2a5c-1b3d-4e6f-8a9b-0c1d2e3f4a5b");
    private static final Instant REQUESTED_AT = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant DEADLINE = REQUESTED_AT.plusSeconds(60);

    private static EventCopyInput input() {
        return new EventCopyInput("겨울 한정 핫초코 50% 할인", EventCopyTone.URGENT, 300,
                Instant.parse("2030-01-02T10:00:00Z"), Instant.parse("2030-01-02T12:00:00Z"));
    }

    private static LlmRequestMessage request() {
        return LlmRequestMessage.eventCopy(REQUEST_ID, 7L, REQUESTED_AT, DEADLINE, false, input());
    }

    private static List<EventCopyCandidate> candidates() {
        return List.of(
                new EventCopyCandidate("선착순 300명! 핫초코 반값", "오늘 10시, 딱 300잔만 반값입니다."),
                new EventCopyCandidate("겨울엔 핫초코", "따뜻한 핫초코를 50% 할인된 가격에 만나 보세요."),
                new EventCopyCandidate("두 시간의 기회", "10시부터 12시까지, 수량이 끝나면 종료됩니다."));
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Nested
    class 요청_메시지 {

        @Test
        void JSON_으로_바꿨다_되돌리면_같다() throws Exception {
            LlmRequestMessage original = request();

            String json = mapper.writeValueAsString(original);
            LlmRequestMessage restored = mapper.readValue(json, LlmRequestMessage.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.schemaVersion()).isEqualTo(LlmTopics.SCHEMA_VERSION);
        }

        @Test
        void 필드_이름과_시각_형식이_고정돼_있다() throws Exception {
            // 이름을 바꾸면 먼저 배포된 쪽이 상대 메시지를 못 읽는다 → 바꾸려면 SCHEMA_VERSION 을 올리고 이 테스트도 고친다
            JsonNode json = mapper.readTree(mapper.writeValueAsString(request()));

            assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                    "schemaVersion", "requestId", "type", "requestedBy", "requestedAt", "deadline", "regenerate", "eventCopy");
            assertThat(fieldNames(json.get("eventCopy"))).containsExactlyInAnyOrder(
                    "keywords", "tone", "totalQuantity", "issueStartAt", "issueEndAt");
            assertThat(json.get("requestId").asText()).isEqualTo(REQUEST_ID.toString());
            assertThat(json.get("type").asText()).isEqualTo("EVENT_COPY");
            assertThat(json.get("deadline").asText()).isEqualTo("2030-01-01T00:01:00Z");   // epoch 숫자가 아닌 ISO 문자열
            assertThat(json.get("eventCopy").get("tone").asText()).isEqualTo("URGENT");
        }

        @Test
        void 고정된_JSON_을_읽을_수_있다() throws Exception {
            // 다른 쪽(또는 이전 배포)이 보낸 메시지 모양 그대로
            String json = """
                    {"schemaVersion":1,"requestId":"0f8e2a5c-1b3d-4e6f-8a9b-0c1d2e3f4a5b","type":"EVENT_COPY",
                     "requestedBy":7,"requestedAt":"2030-01-01T00:00:00Z","deadline":"2030-01-01T00:01:00Z",
                     "regenerate":false,
                     "eventCopy":{"keywords":"겨울 한정 핫초코 50% 할인","tone":"URGENT","totalQuantity":300,
                                  "issueStartAt":"2030-01-02T10:00:00Z","issueEndAt":"2030-01-02T12:00:00Z"}}
                    """;

            assertThat(mapper.readValue(json, LlmRequestMessage.class)).isEqualTo(request());
        }

        @Test
        void 모르는_필드는_무시한다_필드를_추가해도_먼저_배포된_쪽이_깨지지_않는다() throws Exception {
            String json = """
                    {"schemaVersion":1,"requestId":"0f8e2a5c-1b3d-4e6f-8a9b-0c1d2e3f4a5b","type":"EVENT_COPY",
                     "requestedBy":7,"requestedAt":"2030-01-01T00:00:00Z","deadline":"2030-01-01T00:01:00Z",
                     "regenerate":false,"locale":"ko-KR",
                     "eventCopy":{"keywords":"겨울 한정 핫초코 50% 할인","tone":"URGENT","totalQuantity":300,
                                  "issueStartAt":"2030-01-02T10:00:00Z","issueEndAt":"2030-01-02T12:00:00Z",
                                  "brandName":"새 필드"}}
                    """;

            assertThat(mapper.readValue(json, LlmRequestMessage.class)).isEqualTo(request());
        }

        @Test
        void 발급_기간은_비워_보낼_수_있다() throws Exception {
            EventCopyInput partial = new EventCopyInput("봄맞이", EventCopyTone.FRIENDLY, 10, null, null);
            LlmRequestMessage original = LlmRequestMessage.eventCopy(REQUEST_ID, 7L, REQUESTED_AT, DEADLINE, true, partial);

            LlmRequestMessage restored = mapper.readValue(mapper.writeValueAsString(original), LlmRequestMessage.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.regenerate()).isTrue();
        }

        @Test
        void 규칙을_어긴_메시지는_읽는_순간_거절된다() {
            String noInput = """
                    {"schemaVersion":1,"requestId":"0f8e2a5c-1b3d-4e6f-8a9b-0c1d2e3f4a5b","type":"EVENT_COPY",
                     "requestedBy":7,"requestedAt":"2030-01-01T00:00:00Z","deadline":"2030-01-01T00:01:00Z"}
                    """;

            // 컨슈머에서는 역직렬화 실패 → 재시도 없이 DLT (LLM 을 부르지 않는다)
            assertThatThrownBy(() -> mapper.readValue(noInput, LlmRequestMessage.class))
                    .isInstanceOf(ValueInstantiationException.class)
                    .hasMessageContaining("eventCopy");
        }

        @Test
        void 마감_시각이_접수_시각보다_늦어야_하고_마감이_지났는지_알_수_있다() {
            assertThatThrownBy(() -> LlmRequestMessage.eventCopy(REQUEST_ID, 7L, REQUESTED_AT, REQUESTED_AT, false, input()))
                    .isInstanceOf(IllegalArgumentException.class);

            LlmRequestMessage message = request();
            assertThat(message.isExpired(DEADLINE.minusMillis(1))).isFalse();
            assertThat(message.isExpired(DEADLINE)).isTrue();   // 마감 시각 자체부터 만료
        }

        @Test
        void 입력_길이를_제한한다() {
            String tooLong = "가".repeat(EventCopyInput.KEYWORDS_MAX + 1);

            assertThatThrownBy(() -> new EventCopyInput(tooLong, EventCopyTone.FRIENDLY, 1, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new EventCopyInput("  ", EventCopyTone.FRIENDLY, 1, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new EventCopyInput("봄", EventCopyTone.FRIENDLY, 0, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class 결과_메시지 {

        private final Instant completedAt = Instant.parse("2030-01-01T00:00:03Z");

        @Test
        void 성공_결과를_JSON_으로_바꿨다_되돌리면_같다() throws Exception {
            LlmResultMessage original = LlmResultMessage.completed(
                    REQUEST_ID, candidates(), "claude-haiku-5-5", 420, 180, false, 2_300, completedAt);

            LlmResultMessage restored = mapper.readValue(mapper.writeValueAsString(original), LlmResultMessage.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.candidates()).hasSize(LlmResultMessage.CANDIDATE_COUNT);
            assertThat(restored.totalTokens()).isEqualTo(600);
        }

        @Test
        void 실패_결과를_JSON_으로_바꿨다_되돌리면_같다() throws Exception {
            LlmResultMessage original = LlmResultMessage.failed(
                    REQUEST_ID, LlmFailureReason.INVALID_OUTPUT, "claude-haiku-5-5", 400, 900, 8_000, completedAt);

            String json = mapper.writeValueAsString(original);
            LlmResultMessage restored = mapper.readValue(json, LlmResultMessage.class);

            assertThat(restored).isEqualTo(original);
            assertThat(mapper.readTree(json).get("failureReason").asText()).isEqualTo("INVALID_OUTPUT");
            assertThat(restored.totalTokens()).isEqualTo(1_300);   // 실패해도 쓴 토큰은 예산에 잡혀야 한다
        }

        @Test
        void 필드_이름이_고정돼_있다() throws Exception {
            LlmResultMessage message = LlmResultMessage.completed(
                    REQUEST_ID, candidates(), "fake", 0, 0, true, 5, completedAt);
            JsonNode json = mapper.readTree(mapper.writeValueAsString(message));

            assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                    "schemaVersion", "requestId", "status", "candidates", "failureReason", "model",
                    "inputTokens", "outputTokens", "cached", "latencyMs", "completedAt");
            assertThat(fieldNames(json.get("candidates").get(0))).containsExactlyInAnyOrder("name", "description");
        }

        @Test
        void 이전_버전처럼_필드가_빠져_있어도_읽는다() throws Exception {
            // 선택 필드(model, 토큰, cached, latencyMs)가 없는 최소 실패 메시지 → 기본값 0 / false / null
            String json = """
                    {"schemaVersion":1,"requestId":"0f8e2a5c-1b3d-4e6f-8a9b-0c1d2e3f4a5b","status":"FAILED",
                     "failureReason":"LLM_UNAVAILABLE","completedAt":"2030-01-01T00:00:03Z"}
                    """;

            LlmResultMessage message = mapper.readValue(json, LlmResultMessage.class);

            assertThat(message.candidates()).isEmpty();
            assertThat(message.model()).isNull();
            assertThat(message.totalTokens()).isZero();
            assertThat(message.cached()).isFalse();
        }

        @Test
        void 상태와_내용이_맞지_않으면_거절한다() {
            assertThatThrownBy(() -> new LlmResultMessage(1, REQUEST_ID, LlmResultStatus.FAILED, List.of(), null,
                    null, 0, 0, false, 0, completedAt))
                    .as("FAILED 인데 사유 없음").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LlmResultMessage.completed(REQUEST_ID, List.of(), "fake", 0, 0, false, 0, completedAt))
                    .as("COMPLETED 인데 후보 없음").isInstanceOf(IllegalArgumentException.class);
            List<EventCopyCandidate> four = new ArrayList<>(candidates());
            four.add(new EventCopyCandidate("하나 더", "넷째 후보"));
            assertThatThrownBy(() -> LlmResultMessage.completed(REQUEST_ID, four, "fake", 0, 0, false, 0, completedAt))
                    .as("후보가 너무 많음").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LlmResultMessage(1, REQUEST_ID, LlmResultStatus.COMPLETED, candidates(),
                    LlmFailureReason.TIMEOUT, "fake", 0, 0, false, 0, completedAt))
                    .as("COMPLETED 인데 사유 있음").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LlmResultMessage.failed(REQUEST_ID, LlmFailureReason.TIMEOUT, null, -1, 0, 0, completedAt))
                    .as("음수 토큰").isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 후보_길이는_DB_컬럼_길이를_넘지_않는다() {
            assertThatThrownBy(() -> new EventCopyCandidate("가".repeat(EventCopyCandidate.NAME_MAX + 1), "설명"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new EventCopyCandidate("이름", "가".repeat(EventCopyCandidate.DESCRIPTION_MAX + 1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(new EventCopyCandidate("가".repeat(100), "나".repeat(500)).name()).hasSize(100);
        }

        @Test
        void 후보_목록은_바꿀_수_없다() {
            List<EventCopyCandidate> source = new ArrayList<>(candidates());
            LlmResultMessage message = LlmResultMessage.completed(REQUEST_ID, source, "fake", 0, 0, false, 0, completedAt);

            source.clear();   // 원본을 바꿔도 메시지는 그대로 (방어적 복사)

            assertThat(message.candidates()).hasSize(3);
            assertThatThrownBy(() -> message.candidates().add(candidates().get(0)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void 지원하는_스키마_버전() {
        assertThat(LlmTopics.isSupported(LlmTopics.SCHEMA_VERSION)).isTrue();
        assertThat(LlmTopics.isSupported(LlmTopics.SCHEMA_VERSION + 1)).as("모르는 미래 버전").isFalse();
        assertThat(LlmTopics.isSupported(0)).as("필드가 빠져 0 으로 읽힌 경우").isFalse();
    }

    @Test
    void 토픽_이름() {
        assertThat(LlmTopics.REQUESTS).isEqualTo("llm-requests");
        assertThat(LlmTopics.RESULTS).isEqualTo("llm-results");
        assertThat(LlmTopics.REQUESTS_DLT).isEqualTo("llm-requests.DLT");
    }

    @Test
    void 재시도할_수_있는_실패_사유() {
        assertThat(LlmFailureReason.LLM_UNAVAILABLE.isRetryable()).isTrue();
        assertThat(LlmFailureReason.TIMEOUT.isRetryable()).isTrue();
        assertThat(LlmFailureReason.BUDGET_EXCEEDED.isRetryable()).as("내일까지 거절").isFalse();
        assertThat(LlmFailureReason.CONTENT_REFUSED.isRetryable()).as("입력을 바꿔야 함").isFalse();
    }
}
