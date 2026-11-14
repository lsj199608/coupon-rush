package com.couponrush.mcpserver.tool;

import com.couponrush.mcpserver.config.McpProperties;
import com.couponrush.mcpserver.couponapi.CouponApiClient;
import com.couponrush.mcpserver.couponapi.CouponApiDtos.CouponEvent;
import com.couponrush.mcpserver.couponapi.CouponApiDtos.SearchResult;
import com.couponrush.mcpserver.couponapi.CouponApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 조회 전용 MCP 도구. 발급·수정 도구는 만들지 않는다 (발급 경로 LLM 금지) — 대신 결과마다 이벤트 페이지 url 을 넣는다.
 * <ul>
 *   <li>search_events — 자연어 질문으로 이벤트 검색 (coupon-api 자연어 검색, AI 를 못 쓰면 coupon-api 가 키워드 검색으로 대신)</li>
 *   <li>get_event — 이벤트 하나의 기간·남은 수량·상태</li>
 * </ul>
 * get_my_coupons 는 B2(인증 — 사용자 ID 를 서버가 주입)와 함께 추가한다.
 * <p>
 * 실패는 예외가 아니라 isError=true 결과 + 설명 문장으로 돌려준다 (MCP 권장: 모델이 읽고 인자를 고치거나 사용자에게 설명할 수 있게).
 * 결과 본문은 JSON 문자열 — 모델이 필드를 정확히 읽고, 사람이 로그로 봐도 알 수 있게.
 */
@Component
public class EventTools {

    private static final Logger log = LoggerFactory.getLogger(EventTools.class);

    public static final String SEARCH_EVENTS = "search_events";
    public static final String GET_EVENT = "get_event";
    /** coupon-api 검색 질문 상한과 같다 */
    static final int QUERY_MAX = 200;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KST);
    private static final Map<String, String> STATUS_LABELS = Map.of("UPCOMING", "오픈 전", "OPEN", "진행 중", "ENDED", "종료");

    /** 조회만 하고(readOnly), 여러 번 불러도 같고(idempotent), 우리 서비스 안의 데이터만 다룬다(openWorld=false) */
    private static final ToolAnnotations READ_ONLY = ToolAnnotations.builder()
            .readOnlyHint(true).destructiveHint(false).idempotentHint(true).openWorldHint(false).build();

    private final CouponApiClient couponApi;
    private final ObjectMapper objectMapper;
    private final String frontendBaseUrl;

    public EventTools(CouponApiClient couponApi, ObjectMapper objectMapper, McpProperties properties) {
        this.couponApi = couponApi;
        this.objectMapper = objectMapper;
        this.frontendBaseUrl = properties.frontendBaseUrl();
    }

    public List<SyncToolSpecification> specifications(McpJsonMapper jsonMapper) {
        return List.of(searchEvents(jsonMapper), getEvent(jsonMapper));
    }

    // ───────────────────────── search_events ─────────────────────────

    private SyncToolSpecification searchEvents(McpJsonMapper jsonMapper) {
        Tool tool = Tool.builder(SEARCH_EVENTS, jsonMapper, """
                        {"type":"object","additionalProperties":false,"required":["query"],
                         "properties":{"query":{"type":"string","minLength":1,"maxLength":%d,
                           "description":"찾고 싶은 이벤트를 설명하는 한국어 질문 (사용자의 말을 그대로)"}}}
                        """.formatted(QUERY_MAX))
                .title("이벤트 검색")
                .description("""
                        선착순 쿠폰 이벤트를 한국어 자연어 질문으로 찾는다. 예: "이번 주에 끝나는 커피 쿠폰", "진행 중인 치킨 이벤트 남은 거".
                        질문을 그대로 넘기면 서버가 조건(단어·진행 상태·기간·남은 수량)을 만들어 실제 DB 에서 찾는다.
                        결과의 searchMethod 가 KEYWORD 면 AI 조건 대신 질문 속 단어로 찾은 것이라 덜 정확할 수 있다.
                        이 도구로 쿠폰을 발급할 수는 없다 — 발급은 각 이벤트의 url 페이지에서 사용자가 직접 한다.""")
                .annotations(READ_ONLY)
                .build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> handle(SEARCH_EVENTS, request.arguments(), this::doSearchEvents))
                .build();
    }

    private CallToolResult doSearchEvents(Map<String, Object> arguments) {
        Object raw = arguments.get("query");
        if (!(raw instanceof String query) || query.isBlank()) {
            return error("query 에 찾고 싶은 이벤트를 설명하는 질문을 넣어 주세요.");
        }
        String normalized = query.strip().replaceAll("\\s+", " ");
        if (normalized.length() > QUERY_MAX) {
            return error("query 는 " + QUERY_MAX + "자 이하여야 합니다.");
        }
        SearchResult result = couponApi.searchEvents(normalized);
        boolean ai = "COMPLETED".equals(result.status());
        return ok(new SearchView(
                result.query(),
                ai ? "AI_FILTER" : "KEYWORD",
                ai ? result.filter() : null,
                ai ? List.of() : nullToEmpty(result.keywords()),
                ai ? null : result.fallbackReason(),
                nullToEmpty(result.events()).size(),
                nullToEmpty(result.events()).stream().map(this::view).toList()));
    }

    /**
     * @param searchMethod   AI_FILTER = AI 가 만든 조건으로 찾음 / KEYWORD = AI 를 못 써서 질문 속 단어 중 하나라도 들어 있는 이벤트
     * @param conditions     AI_FILTER 일 때 쓴 조건 (기간은 [from, to), UTC)
     * @param keywords       KEYWORD 일 때 쓴 단어
     * @param fallbackReason KEYWORD 가 된 이유 (RATE_LIMITED, BUDGET_EXCEEDED, TIMEOUT, LLM_UNAVAILABLE)
     */
    record SearchView(String query, String searchMethod, Object conditions, List<String> keywords, String fallbackReason,
                      int count, List<EventView> events) {
    }

    // ───────────────────────── get_event ─────────────────────────

    private SyncToolSpecification getEvent(McpJsonMapper jsonMapper) {
        Tool tool = Tool.builder(GET_EVENT, jsonMapper, """
                        {"type":"object","additionalProperties":false,"required":["eventId"],
                         "properties":{"eventId":{"type":"integer","minimum":1,"description":"이벤트 ID (search_events 결과의 id)"}}}
                        """)
                .title("이벤트 상세")
                .description("""
                        이벤트 하나의 이름·설명·발급 기간·진행 상태·남은 수량과 페이지 url 을 가져온다.
                        eventId 는 search_events 결과의 id 를 쓴다. 남은 수량은 조회 시점 값이라 금방 바뀔 수 있다.""")
                .annotations(READ_ONLY)
                .build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> handle(GET_EVENT, request.arguments(), this::doGetEvent))
                .build();
    }

    private CallToolResult doGetEvent(Map<String, Object> arguments) {
        Long eventId = positiveLong(arguments.get("eventId"));
        if (eventId == null) {
            return error("eventId 에 1 이상의 정수 이벤트 ID 를 넣어 주세요.");
        }
        return ok(view(couponApi.getEvent(eventId)));
    }

    /** JSON 숫자는 Integer·Long·Double 로 올 수 있다. 소수(1.5)·문자열·0 이하는 거절 */
    private static Long positiveLong(Object raw) {
        if (raw instanceof Integer || raw instanceof Long) {
            long value = ((Number) raw).longValue();
            return value >= 1 ? value : null;
        }
        if (raw instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue()) && number.doubleValue() >= 1) {
            return number.longValue();
        }
        return null;
    }

    // ───────────────────────── 공통 ─────────────────────────

    /**
     * @param status      UPCOMING / OPEN / ENDED (statusLabel 은 한국어)
     * @param issueStart  발급 시작 (한국 시각, 포함)
     * @param issueEnd    발급 종료 (한국 시각, 이 시각부터는 발급 안 됨)
     * @param url         이벤트 페이지 — 발급은 여기서 사용자가 직접
     */
    record EventView(long id, String name, String description, String status, String statusLabel,
                     int remainingQuantity, int totalQuantity, String issueStart, String issueEnd, String url) {
    }

    private EventView view(CouponEvent event) {
        return new EventView(event.id(), event.name(), event.description(), event.status(),
                STATUS_LABELS.getOrDefault(event.status(), event.status()),
                event.remainingQuantity(), event.totalQuantity(), kst(event.issueStartAt()), kst(event.issueEndAt()),
                frontendBaseUrl + "/events/" + event.id());
    }

    private static String kst(Instant instant) {
        return instant == null ? null : KST_TIME.format(instant) + " KST";
    }

    private CallToolResult handle(String toolName, Map<String, Object> arguments, Function<Map<String, Object>, CallToolResult> body) {
        try {
            return body.apply(arguments == null ? Map.of() : arguments);
        } catch (CouponApiException e) {
            return error(switch (e.kind()) {
                case NOT_FOUND -> "찾을 수 없습니다: " + e.getMessage();
                case INVALID_INPUT -> "입력이 올바르지 않습니다: " + e.getMessage();
                case UNAVAILABLE -> e.getMessage();
            });
        } catch (RuntimeException e) {
            log.error("도구 실행 중 예상하지 못한 오류: tool={}", toolName, e);
            return error("도구 실행 중 문제가 생겼습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private CallToolResult ok(Object payload) {
        try {
            return CallToolResult.builder().addTextContent(objectMapper.writeValueAsString(payload)).isError(false).build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("도구 결과 직렬화 실패", e);
        }
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
