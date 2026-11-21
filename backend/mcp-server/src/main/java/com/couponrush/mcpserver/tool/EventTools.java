package com.couponrush.mcpserver.tool;

import com.couponrush.mcpserver.auth.Caller;
import com.couponrush.mcpserver.couponapi.CouponApiException;
import com.couponrush.mcpserver.event.EventQueryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 조회 전용 MCP 도구 — MCP 쪽 입구(컨트롤러 역할). 도구 정의(스키마·설명)·인자 검사·결과/오류 감싸기만 하고,
 * 조회와 결과 가공은 {@link EventQueryService} 에 맡긴다.
 * <ul>
 *   <li>search_events — 자연어 질문으로 이벤트 검색 (coupon-api 자연어 검색, AI 를 못 쓰면 coupon-api 가 키워드 검색으로 대신)</li>
 *   <li>get_event — 이벤트 하나의 기간·남은 수량·상태</li>
 *   <li>get_my_coupons — 호출한 사용자 본인이 받은 쿠폰 (로그인 필요)</li>
 * </ul>
 * 발급·수정 도구는 만들지 않는다 (발급 경로 LLM 금지) — 대신 결과마다 이벤트 페이지 url 이 들어 있다.
 * 누구인지는 도구 인자가 아니라 연결의 HTTP 헤더(Caller)로만 정한다 — get_my_coupons 에는 인자가 없다.
 * <p>
 * 실패는 예외가 아니라 isError=true 결과 + 설명 문장으로 돌려준다 (MCP 권장: 모델이 읽고 인자를 고치거나 사용자에게 설명할 수 있게).
 * 결과 본문은 JSON 문자열 — 모델이 필드를 정확히 읽고, 사람이 로그로 봐도 알 수 있게.
 */
@Component
public class EventTools {

    private static final Logger log = LoggerFactory.getLogger(EventTools.class);

    public static final String SEARCH_EVENTS = "search_events";
    public static final String GET_EVENT = "get_event";
    public static final String GET_MY_COUPONS = "get_my_coupons";
    /** coupon-api 검색 질문 상한과 같다 */
    static final int QUERY_MAX = 200;

    /** 조회만 하고(readOnly), 여러 번 불러도 같고(idempotent), 우리 서비스 안의 데이터만 다룬다(openWorld=false) */
    private static final ToolAnnotations READ_ONLY = ToolAnnotations.builder()
            .readOnlyHint(true).destructiveHint(false).idempotentHint(true).openWorldHint(false).build();

    private final EventQueryService eventQueryService;
    private final ObjectMapper objectMapper;

    public EventTools(EventQueryService eventQueryService, ObjectMapper objectMapper) {
        this.eventQueryService = eventQueryService;
        this.objectMapper = objectMapper;
    }

    public List<SyncToolSpecification> specifications(McpJsonMapper jsonMapper) {
        return List.of(searchEvents(jsonMapper), getEvent(jsonMapper), getMyCoupons(jsonMapper));
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
                .callHandler((exchange, request) -> handle(SEARCH_EVENTS, exchange, request.arguments(), this::doSearchEvents))
                .build();
    }

    private CallToolResult doSearchEvents(Map<String, Object> arguments, Caller caller) {
        Object raw = arguments.get("query");
        if (!(raw instanceof String query) || query.isBlank()) {
            return error("query 에 찾고 싶은 이벤트를 설명하는 질문을 넣어 주세요.");
        }
        String normalized = query.strip().replaceAll("\\s+", " ");
        if (normalized.length() > QUERY_MAX) {
            return error("query 는 " + QUERY_MAX + "자 이하여야 합니다.");
        }
        return ok(eventQueryService.search(normalized, caller));
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
                .callHandler((exchange, request) -> handle(GET_EVENT, exchange, request.arguments(), this::doGetEvent))
                .build();
    }

    private CallToolResult doGetEvent(Map<String, Object> arguments, Caller caller) {
        Long eventId = positiveLong(arguments.get("eventId"));
        if (eventId == null) {
            return error("eventId 에 1 이상의 정수 이벤트 ID 를 넣어 주세요.");
        }
        return ok(eventQueryService.getEvent(eventId));
    }

    // ───────────────────────── get_my_coupons ─────────────────────────

    private SyncToolSpecification getMyCoupons(McpJsonMapper jsonMapper) {
        // 인자 없음: "누구의 쿠폰인지"를 모델이 고르지 못하게 (프롬프트 인젝션으로 남의 쿠폰을 보는 것 방지)
        Tool tool = Tool.builder(GET_MY_COUPONS, jsonMapper, """
                        {"type":"object","additionalProperties":false,"properties":{}}
                        """)
                .title("내 쿠폰")
                .description("""
                        지금 로그인한 사용자 본인이 받은 쿠폰 목록(이벤트 이름·받은 시각·이벤트 페이지 url)을 가져온다. 인자는 없다.
                        사용자는 MCP 연결 설정의 인증으로 정해지며 다른 사람의 쿠폰은 볼 수 없다.
                        로그인이 필요하다는 오류가 오면 사용자에게 다시 로그인해 access token 을 연결 설정에 넣어 달라고 안내한다.""")
                .annotations(READ_ONLY)
                .build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> handle(GET_MY_COUPONS, exchange, request.arguments(), this::doGetMyCoupons))
                .build();
    }

    private CallToolResult doGetMyCoupons(Map<String, Object> arguments, Caller caller) {
        return ok(eventQueryService.getMyCoupons(caller));
    }

    // ───────────────────────── 공통 ─────────────────────────

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

    /** 호출자 확인 → 본문 실행 → coupon-api 실패·예상 못 한 오류를 설명 문장(isError)으로. */
    private CallToolResult handle(String toolName, McpSyncServerExchange exchange, Map<String, Object> arguments,
                                  BiFunction<Map<String, Object>, Caller, CallToolResult> body) {
        try {
            Caller caller = Caller.of(exchange == null ? null : exchange.transportContext());
            return body.apply(arguments == null ? Map.of() : arguments, caller);
        } catch (CouponApiException e) {
            return error(switch (e.kind()) {
                case NOT_FOUND -> "찾을 수 없습니다: " + e.getMessage();
                case INVALID_INPUT -> "입력이 올바르지 않습니다: " + e.getMessage();
                case UNAUTHENTICATED -> "로그인이 필요합니다: " + e.getMessage()
                        + " 사용자에게 웹에서 로그인한 뒤 받은 access token 을 MCP 연결 설정의 Authorization: Bearer 헤더에 넣어 달라고 안내하세요.";
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
}
