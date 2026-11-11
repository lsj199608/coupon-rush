package com.couponrush.llmservice.client.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnprocessableEntityException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.anthropic.models.messages.StructuredTextBlock;
import com.couponrush.llm.contract.EventCopyCandidate;
import com.couponrush.llm.contract.EventCopyInput;
import com.couponrush.llm.contract.EventSearchFilter;
import com.couponrush.llm.contract.EventSearchInput;
import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llm.contract.LlmRequestType;
import com.couponrush.llmservice.LlmServiceProperties;
import com.couponrush.llmservice.client.LlmCallException;
import com.couponrush.llmservice.client.LlmClient;
import com.couponrush.llmservice.client.LlmCompletion;
import com.couponrush.llmservice.client.LlmSearchCompletion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Anthropic(Claude) 제공자. 공식 Java SDK 로 Messages API 를 한 번(필요하면 재생성 한 번 더) 부른다.
 * <ul>
 *   <li>출력: 구조화된 출력(output_config.format = EventCopyOutput 의 JSON 스키마) → 모양은 API 가 보장, 개수·길이는 검증기가 확인</li>
 *   <li>재시도: SDK 내장(429·5xx·연결 오류만, maxRetries). 400 같은 요청 오류는 재시도하지 않는다 — 다시 보내도 같고 비용만 든다</li>
 *   <li>서킷 브레이커: 제공자 장애(429·5xx·연결·타임아웃·인증 오류)가 몰리면 잠시 호출하지 않고 바로 LLM_UNAVAILABLE</li>
 *   <li>거절(stop_reason=refusal): CONTENT_REFUSED. 서버 측 fallbacks("default")가 켜져 있으면 일부 범주는 API 가 다른 모델로 먼저 재시도한다</li>
 *   <li>출력 검증 실패·max_tokens 로 잘림: 1회 재생성, 그래도 실패면 INVALID_OUTPUT (두 번의 토큰을 모두 보고 → 예산에 반영)</li>
 * </ul>
 * 기능은 두 가지이고 위 처리(재시도·서킷·거절·재생성)는 {@link #generate} 하나를 함께 쓴다 — 프롬프트·출력 형식·검증기만 다르다.
 * <ul>
 *   <li>문구 생성(EVENT_COPY): event-copy 프롬프트 → {@link EventCopyOutput} → 후보 3개</li>
 *   <li>자연어 검색(EVENT_SEARCH): event-search 프롬프트 → {@link EventSearchOutput} → 검색 조건 (이벤트 목록이 아님)</li>
 * </ul>
 * 프롬프트 인젝션: 사용자 입력은 &lt;event_input&gt; / &lt;search_input&gt; 안의 JSON 데이터로만 넣고,
 * 시스템 프롬프트가 그 안의 지시를 따르지 말라고 못박는다. 문구는 관리자가 확인·수정한 뒤에만 저장되고,
 * 검색 조건은 검증기를 통과한 값만 DB 조회에 쓰인다 (조건 밖의 일은 할 수 없다).
 */
public class AnthropicLlmClient implements LlmClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

    static final String PROMPT_VERSION = "event-copy.v1";
    static final String SEARCH_PROMPT_VERSION = "event-search.v1";
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final int MAX_ATTEMPTS = 2;   // 최초 1회 + 출력 검증 실패 시 재생성 1회
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AnthropicClient client;
    private final LlmServiceProperties.Anthropic config;
    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;
    private final String copyPrompt;
    private final String searchPrompt;

    public AnthropicLlmClient(LlmServiceProperties.Anthropic config, LlmServiceProperties.CircuitBreaker breaker,
                              ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.copyPrompt = loadPrompt(PROMPT_VERSION);
        this.searchPrompt = loadPrompt(SEARCH_PROMPT_VERSION);
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .timeout(config.timeout())
                .maxRetries(config.maxRetries())
                .build();
        this.circuitBreaker = CircuitBreaker.of("anthropic", CircuitBreakerConfig.custom()
                .failureRateThreshold(breaker.failureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(breaker.slidingWindowSize())
                .minimumNumberOfCalls(breaker.minimumNumberOfCalls())
                .waitDurationInOpenState(breaker.openDuration())
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
        this.circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("Anthropic 서킷 브레이커 {}", event.getStateTransition()));
    }

    @Override
    public LlmCompletion generateEventCopy(EventCopyInput input) {
        Generated<List<EventCopyCandidate>> generated = generate(params(input), output -> {
            EventCopyOutputValidator.Result result = EventCopyOutputValidator.validate(output);
            return result.valid() ? Validated.ok(result.candidates()) : Validated.invalid(result.problem());
        });
        return new LlmCompletion(generated.value(), generated.model(), generated.inputTokens(), generated.outputTokens());
    }

    @Override
    public LlmSearchCompletion extractSearchFilter(EventSearchInput input, LocalDate today) {
        Generated<EventSearchFilter> generated = generate(searchParams(input, today), output -> {
            EventSearchOutputValidator.Result result = EventSearchOutputValidator.validate(output);
            return result.valid() ? Validated.ok(result.filter()) : Validated.invalid(result.problem());
        });
        return new LlmSearchCompletion(generated.value(), generated.model(), generated.inputTokens(), generated.outputTokens());
    }

    /** 검증 결과: 통과하면 value, 아니면 problem (재생성 대상) */
    private record Validated<R>(R value, String problem) {
        static <R> Validated<R> ok(R value) {
            return new Validated<>(value, null);
        }

        static <R> Validated<R> invalid(String problem) {
            return new Validated<>(null, problem);
        }
    }

    private record Generated<R>(R value, String model, int inputTokens, int outputTokens) {
    }

    /**
     * 호출 → 거절이면 CONTENT_REFUSED → 잘림·검증 실패면 1회 재생성 → 그래도면 INVALID_OUTPUT. 토큰은 모든 시도를 합산한다.
     */
    private <T, R> Generated<R> generate(StructuredMessageCreateParams<T> params, Function<T, Validated<R>> validator) {
        int inputTokens = 0;
        int outputTokens = 0;
        String model = config.model();
        String lastProblem = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            StructuredMessage<T> message = call(params, model, inputTokens, outputTokens);
            inputTokens += (int) message.usage().inputTokens();
            outputTokens += (int) message.usage().outputTokens();
            model = message.model().asString();   // 서버 측 fallback 이 일어나면 실제로 답한 모델이 다르다

            Optional<StopReason> stop = message.stopReason();
            if (stop.isPresent() && stop.get().equals(StopReason.REFUSAL)) {
                String category = message.stopDetails().flatMap(d -> d.category()).map(Object::toString).orElse("unknown");
                throw new LlmCallException(LlmFailureReason.CONTENT_REFUSED, "모델이 생성을 거절함: category=" + category,
                        model, inputTokens, outputTokens, null);
            }
            if (stop.isPresent() && stop.get().equals(StopReason.MAX_TOKENS)) {
                lastProblem = "max_tokens(" + config.maxTokens() + ")에서 잘림";
            } else {
                Validated<R> result = validator.apply(parse(message));
                if (result.problem() == null) {
                    return new Generated<>(result.value(), model, inputTokens, outputTokens);
                }
                lastProblem = result.problem();
            }
            log.warn("출력 검증 실패 (시도 {}/{}): {}", attempt, MAX_ATTEMPTS, lastProblem);
        }
        throw new LlmCallException(LlmFailureReason.INVALID_OUTPUT, "재생성 후에도 출력 규칙 위반: " + lastProblem,
                model, inputTokens, outputTokens, null);
    }

    @Override
    public String cacheIdentity(LlmRequestType type) {
        String prompt = switch (type) {
            case EVENT_COPY -> PROMPT_VERSION;   // 기존 값 그대로 — 이미 쌓인 문구 캐시를 유지
            case EVENT_SEARCH -> SEARCH_PROMPT_VERSION;
        };
        return "anthropic:" + config.model() + ":" + prompt + ":effort=" + config.effort();
    }

    StructuredMessageCreateParams<EventCopyOutput> params(EventCopyInput input) {
        return build(copyPrompt, EventCopyOutput.class, "<event_input>\n" + inputJson(input) + "\n</event_input>");
    }

    StructuredMessageCreateParams<EventSearchOutput> searchParams(EventSearchInput input, LocalDate today) {
        return build(searchPrompt, EventSearchOutput.class, "<search_input>\n" + searchJson(input, today) + "\n</search_input>");
    }

    /** 두 기능 공통 요청 설정 — 모델·max_tokens·effort·구조화된 출력·fallbacks */
    private <T> StructuredMessageCreateParams<T> build(String system, Class<T> outputType, String userMessage) {
        StructuredMessageCreateParams.Builder<T> builder = MessageCreateParams.builder()
                .model(config.model())
                .maxTokens(config.maxTokens())
                .system(system)
                .outputConfig(StructuredOutputConfig.<T>builder()
                        .effort(OutputConfig.Effort.of(config.effort()))
                        .format(outputType, JsonSchemaLocalValidation.YES)
                        .build())
                .addUserMessage(userMessage);
        if (config.fallbacks()) {
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder.build();
    }

    /**
     * 한 번의 API 호출 (SDK 가 429·5xx·연결 오류를 maxRetries 만큼 재시도한 뒤의 최종 결과).
     * 실패는 모두 LLM_UNAVAILABLE — 다만 서킷 브레이커에는 "제공자 쪽 문제"만 실패로 센다.
     */
    private <T> StructuredMessage<T> call(StructuredMessageCreateParams<T> params, String model,
                                          int inputTokens, int outputTokens) {
        if (!circuitBreaker.tryAcquirePermission()) {
            throw new LlmCallException(LlmFailureReason.LLM_UNAVAILABLE, "서킷 브레이커 열림 — 호출하지 않음",
                    model, inputTokens, outputTokens, null);
        }
        long start = System.nanoTime();
        try {
            StructuredMessage<T> message = client.messages().create(params);
            circuitBreaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return message;
        } catch (BadRequestException | UnprocessableEntityException e) {
            // 요청 자체가 잘못됨 (프롬프트·파라미터·모델 설정). 제공자 장애가 아니라 서킷에는 세지 않는다 — 대신 에러 로그로 드러낸다
            circuitBreaker.releasePermission();
            log.error("Anthropic 이 요청을 거부함 (설정·요청 형식 확인 필요): status={}, model={}", e.statusCode(), model, e);
            throw unavailable("요청 거부 " + e.statusCode(), model, inputTokens, outputTokens, e);
        } catch (RateLimitException | InternalServerException e) {
            circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            throw unavailable("제공자 " + e.statusCode() + " (SDK 재시도 소진)", model, inputTokens, outputTokens, e);
        } catch (AnthropicServiceException e) {
            // 401/403/404 등: 키·권한·모델 ID 문제 — 계속 실패할 것이므로 서킷에도 센다
            circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            log.error("Anthropic 호출 실패: status={}, type={}", e.statusCode(), e.errorType().orElse(null), e);
            throw unavailable("제공자 오류 " + e.statusCode(), model, inputTokens, outputTokens, e);
        } catch (AnthropicIoException e) {
            circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            throw unavailable("연결 실패·타임아웃", model, inputTokens, outputTokens, e);
        } catch (AnthropicException e) {
            circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            throw unavailable("SDK 오류: " + e.getMessage(), model, inputTokens, outputTokens, e);
        }
    }

    /** 구조화된 출력을 레코드로 읽는다. 형식이 깨졌으면 null → 검증 실패로 처리 (재생성 대상) */
    private <T> T parse(StructuredMessage<T> message) {
        try {
            return message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(StructuredTextBlock::text)
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("구조화된 출력 해석 실패: {}", e.getMessage());
            return null;
        }
    }

    /** 프롬프트에 넣는 입력 — 데이터로만 쓰이도록 JSON 으로 (시각은 한국 시각 문자열로 바꿔 모델이 계산하지 않게) */
    private String inputJson(EventCopyInput input) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keywords", input.keywords());
        data.put("tone", input.tone().name());
        data.put("totalQuantity", input.totalQuantity());
        data.put("issueStart", kst(input.issueStartAt()));
        data.put("issueEnd", kst(input.issueEndAt()));
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("프롬프트 입력 직렬화 실패", e);
        }
    }

    /** 검색 입력 — 상대 날짜 계산의 기준은 오늘 날짜·요일만 준다 (시각을 주면 같은 날 같은 질문의 조건이 달라질 수 있다) */
    private String searchJson(EventSearchInput input, LocalDate today) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", input.query());
        data.put("today", today.toString());
        data.put("weekday", today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN));
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("프롬프트 입력 직렬화 실패", e);
        }
    }

    private static String kst(Instant instant) {
        return instant == null ? null : KST_TIME.format(instant.atZone(KST)) + " (KST)";
    }

    private static LlmCallException unavailable(String message, String model, int inputTokens, int outputTokens, Throwable cause) {
        return new LlmCallException(LlmFailureReason.LLM_UNAVAILABLE, message, model, inputTokens, outputTokens, cause);
    }

    private static String loadPrompt(String version) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource("prompts/" + version + ".txt").getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    @Override
    public void close() {
        client.close();
    }
}
