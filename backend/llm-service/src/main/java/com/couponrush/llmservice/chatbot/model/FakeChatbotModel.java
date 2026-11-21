package com.couponrush.llmservice.chatbot.model;

import com.couponrush.llm.contract.ChatbotInput;
import com.couponrush.llm.contract.ChatbotReply;
import com.couponrush.llmservice.chatbot.tool.ToolCall;
import com.couponrush.llmservice.chatbot.tool.ToolOutcome;
import com.couponrush.llmservice.chatbot.tool.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 가짜 챗봇 판단 (기본 제공자, LLM 호출 없음). <b>미리 정해 둔 말 규칙</b>으로 도구를 하나 고르고, 도구 결과를 틀에 넣어 답한다.
 * 도구 호출·mcp-server·coupon-api·DB 조회·상한·마감은 진짜로 일어난다 — 대신하는 것은 "판단"뿐이라 흐름 확인용이다.
 * <ul>
 *   <li>발급 요청("발급해줘", "받아줘" …) → 발급은 하지 않고, 이벤트를 찾아 페이지 링크로 안내</li>
 *   <li>내 쿠폰("내 쿠폰", "받은 쿠폰" …) → get_my_coupons</li>
 *   <li>"3번 이벤트", "이벤트 3" → get_event</li>
 *   <li>인사·사용법 → 도구 없이 안내</li>
 *   <li>그 밖의 문장 → search_events 에 문장 그대로</li>
 * </ul>
 * 지난 대화는 쓰지 않는다 ("그거 언제 끝나?" 같은 말은 이해하지 못함 — 실제 모델로 확인할 부분).
 */
public class FakeChatbotModel implements ChatbotModel {

    public static final String MODEL = "fake";

    static final String SEARCH = "search_events";
    static final String GET_EVENT = "get_event";
    static final String MY_COUPONS = "get_my_coupons";

    private static final List<String> ISSUE_WORDS = List.of(
            "발급해", "발급 해", "받아줘", "받아 줘", "받게 해", "신청해", "발급받고 싶", "받고 싶");
    private static final List<String> MY_COUPON_WORDS = List.of(
            "내 쿠폰", "내쿠폰", "받은 쿠폰", "받은 거", "발급받은", "내가 받은", "보유");
    private static final List<String> HELP_WORDS = List.of("안녕", "도움말", "사용법", "뭘 할 수", "뭐 할 수");
    private static final Pattern EVENT_ID = Pattern.compile("(\\d+)\\s*번\\s*이벤트|이벤트\\s*(\\d+)\\s*번?");
    private static final int LIST_MAX = 5;

    static final String ISSUE_GUIDE = "쿠폰 발급은 제가 대신 할 수 없어요. 이벤트 페이지에서 직접 [발급 받기]를 눌러 주세요.";
    static final String HELP = "이벤트 찾기(예: \"이번 주에 끝나는 커피 쿠폰\"), 이벤트 자세히 보기(예: \"3번 이벤트\"), "
            + "내가 받은 쿠폰 보기(예: \"내 쿠폰 보여줘\")를 도와드릴 수 있어요. 쿠폰 발급은 이벤트 페이지에서 직접 해 주세요.";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ChatbotConversation start(ChatbotInput input, List<ToolSpec> tools, LocalDate today) {
        return new Conversation(input, tools.stream().map(ToolSpec::name).collect(Collectors.toSet()));
    }

    private enum Intent { HELP, ISSUE, MY_COUPONS, EVENT, SEARCH }

    private final class Conversation implements ChatbotConversation {

        private final ChatbotInput input;
        private final Set<String> toolNames;
        private final Intent intent;
        private ToolCall pending;
        private ToolOutcome outcome;

        Conversation(ChatbotInput input, Set<String> toolNames) {
            this.input = input;
            this.toolNames = toolNames;
            this.intent = intentOf(input.message());
        }

        @Override
        public ModelTurn next(boolean toolsAllowed) {
            int inputTokens = 100 + input.message().length() / 2 + input.history().size() * 20
                    + (outcome == null ? 0 : outcome.text().length() / 4);
            if (outcome == null && intent != Intent.HELP) {
                ToolCall call = callFor(intent, input.message());
                if (toolsAllowed && toolNames.contains(call.name())) {
                    pending = call;
                    return new ModelTurn.ToolRequests(List.of(call), MODEL, inputTokens, 20);
                }
                return answer("지금은 이벤트 정보를 조회할 수 없어요. 잠시 후 다시 물어봐 주세요.", inputTokens);
            }
            return answer(outcome == null ? HELP : compose(), inputTokens);
        }

        @Override
        public void addToolResults(List<ToolOutcome> results) {
            if (pending == null || results.size() != 1 || !results.get(0).callId().equals(pending.id())) {
                throw new IllegalStateException("요청한 도구 호출과 결과가 맞지 않습니다");
            }
            outcome = results.get(0);
            pending = null;
        }

        private ModelTurn answer(String text, int inputTokens) {
            String fitted = text.length() <= ChatbotReply.TEXT_MAX ? text : text.substring(0, ChatbotReply.TEXT_MAX - 1) + "…";
            return new ModelTurn.Answer(fitted, MODEL, inputTokens, fitted.length() / 2);
        }

        private String compose() {
            String body = outcome.isError() ? "조회하지 못했어요: " + outcome.text() : switch (intent) {
                case MY_COUPONS -> myCoupons(read(outcome.text()));
                case EVENT -> event(read(outcome.text()));
                default -> events(read(outcome.text()));
            };
            return intent == Intent.ISSUE ? ISSUE_GUIDE + "\n" + body : body;
        }
    }

    static Intent intentOf(String message) {
        if (containsAny(message, ISSUE_WORDS)) {
            return Intent.ISSUE;
        }
        if (containsAny(message, MY_COUPON_WORDS)) {
            return Intent.MY_COUPONS;
        }
        if (EVENT_ID.matcher(message).find()) {
            return Intent.EVENT;
        }
        if (containsAny(message, HELP_WORDS)) {
            return Intent.HELP;
        }
        return Intent.SEARCH;
    }

    private static ToolCall callFor(Intent intent, String message) {
        String id = "fake-" + UUID.randomUUID();
        return switch (intent) {
            case MY_COUPONS -> new ToolCall(id, MY_COUPONS, Map.of());
            case EVENT -> new ToolCall(id, GET_EVENT, Map.of("eventId", eventIdOf(message)));
            case ISSUE -> new ToolCall(id, SEARCH, Map.of("query", withoutIssueWords(message)));
            default -> new ToolCall(id, SEARCH, Map.of("query", message));
        };
    }

    private static long eventIdOf(String message) {
        Matcher matcher = EVENT_ID.matcher(message);
        matcher.find();
        return Long.parseLong(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
    }

    /** "커피 쿠폰 발급해줘" → "커피 쿠폰" (검색어에 발급 말이 남으면 키워드 검색에서 아무것도 안 걸린다) */
    static String withoutIssueWords(String message) {
        String cleaned = message;
        for (String word : ISSUE_WORDS) {
            int at = cleaned.indexOf(word);
            if (at >= 0) {
                cleaned = cleaned.substring(0, at);
            }
        }
        cleaned = cleaned.strip();
        return cleaned.isEmpty() ? message : cleaned;
    }

    private static boolean containsAny(String text, List<String> words) {
        return words.stream().anyMatch(text::contains);
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static String events(JsonNode search) {
        JsonNode events = search.path("events");
        if (!events.isArray() || events.isEmpty()) {
            return "조건에 맞는 이벤트가 없어요.";
        }
        StringBuilder text = new StringBuilder("찾은 이벤트 ").append(events.size()).append("개예요.");
        for (int i = 0; i < Math.min(events.size(), LIST_MAX); i++) {
            JsonNode e = events.get(i);
            text.append("\n- ").append(e.path("name").asText()).append(" (").append(e.path("statusLabel").asText())
                    .append(", 남은 수량 ").append(e.path("remainingQuantity").asInt()).append('/')
                    .append(e.path("totalQuantity").asInt()).append(") ").append(e.path("url").asText());
        }
        if (events.size() > LIST_MAX) {
            text.append("\n그 밖에 ").append(events.size() - LIST_MAX).append("개가 더 있어요.");
        }
        if ("KEYWORD".equals(search.path("searchMethod").asText())) {
            text.append("\n(AI 해석 없이 단어로 찾은 결과예요)");
        }
        return text.toString();
    }

    private static String event(JsonNode e) {
        return e.path("name").asText() + " — " + e.path("statusLabel").asText()
                + ", 남은 수량 " + e.path("remainingQuantity").asInt() + "/" + e.path("totalQuantity").asInt()
                + ", 발급 기간 " + e.path("issueStart").asText() + " ~ " + e.path("issueEnd").asText()
                + "\n" + e.path("url").asText();
    }

    private static String myCoupons(JsonNode mine) {
        JsonNode coupons = mine.path("coupons");
        if (!coupons.isArray() || coupons.isEmpty()) {
            return "아직 받은 쿠폰이 없어요.";
        }
        StringBuilder text = new StringBuilder("받은 쿠폰은 ").append(coupons.size()).append("개예요.");
        for (int i = 0; i < Math.min(coupons.size(), LIST_MAX); i++) {
            JsonNode c = coupons.get(i);
            text.append("\n- ").append(c.path("eventName").asText()).append(" (").append(c.path("issuedAt").asText())
                    .append(") ").append(c.path("url").asText());
        }
        if (coupons.size() > LIST_MAX) {
            text.append("\n그 밖에 ").append(coupons.size() - LIST_MAX).append("개가 더 있어요.");
        }
        return text.toString();
    }
}
