package com.couponrush.llmservice.chatbot;

import com.couponrush.llm.contract.ChatbotInput;
import com.couponrush.llm.contract.ChatbotReply;
import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llmservice.chatbot.model.ChatbotConversation;
import com.couponrush.llmservice.chatbot.model.ChatbotModel;
import com.couponrush.llmservice.chatbot.model.ModelTurn;
import com.couponrush.llmservice.chatbot.tool.ToolCall;
import com.couponrush.llmservice.chatbot.tool.ToolGateway;
import com.couponrush.llmservice.chatbot.tool.ToolOutcome;
import com.couponrush.llmservice.chatbot.tool.ToolSession;
import com.couponrush.llmservice.chatbot.tool.ToolSpec;
import com.couponrush.llmservice.client.LlmCallException;
import com.couponrush.llmservice.client.TestProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** tool use 루프의 상한·마감·토큰 합산 (가짜 모델·가짜 도구 — 순서대로 정해 둔 응답을 낸다) */
class ChatbotOrchestratorTest {

    private final Instant now = Instant.parse("2030-01-01T00:00:00Z");
    private final Instant deadline = now.plusSeconds(60);
    private final ChatbotInput input = new ChatbotInput("커피 쿠폰 있어?", List.of());

    // ───────────────────────── 가짜들 ─────────────────────────

    /** 정해 둔 순서로 답하는 모델. next 에 넘어온 toolsAllowed 와 받은 도구 결과를 기록한다 */
    static final class ScriptedModel implements ChatbotModel {
        final Deque<Function<Boolean, ModelTurn>> script = new ArrayDeque<>();
        final List<Boolean> toolsAllowed = new ArrayList<>();
        final List<List<ToolOutcome>> results = new ArrayList<>();
        List<ToolSpec> offeredTools;

        ScriptedModel then(ModelTurn turn) {
            script.add(allowed -> turn);
            return this;
        }

        ScriptedModel thenFail(LlmCallException e) {
            script.add(allowed -> {
                throw e;
            });
            return this;
        }

        @Override
        public ChatbotConversation start(ChatbotInput input, List<ToolSpec> tools, LocalDate today) {
            offeredTools = tools;
            return new ChatbotConversation() {
                @Override
                public ModelTurn next(boolean allowed) {
                    toolsAllowed.add(allowed);
                    if (script.isEmpty()) {
                        throw new AssertionError("모델이 예상보다 많이 불렸다");
                    }
                    return script.poll().apply(allowed);
                }

                @Override
                public void addToolResults(List<ToolOutcome> outcomes) {
                    results.add(outcomes);
                }
            };
        }
    }

    static final class FakeTools implements ToolGateway {
        final List<ToolCall> executed = new ArrayList<>();
        Long openedFor;
        boolean closed;

        @Override
        public ToolSession open(long userId) {
            openedFor = userId;
            return new ToolSession() {
                @Override
                public List<ToolSpec> tools() {
                    return List.of(new ToolSpec("search_events", "검색", Map.of("type", "object")));
                }

                @Override
                public ToolOutcome call(ToolCall call) {
                    executed.add(call);
                    return new ToolOutcome(call.id(), "{\"count\":0}", false);
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }
    }

    private static ModelTurn answer(String text, int in, int out) {
        return new ModelTurn.Answer(text, "m", in, out);
    }

    private static ModelTurn tools(int in, int out, String... ids) {
        List<ToolCall> calls = new ArrayList<>();
        for (String id : ids) {
            calls.add(new ToolCall(id, "search_events", Map.of("query", "커피")));
        }
        return new ModelTurn.ToolRequests(calls, "m", in, out);
    }

    private ChatbotOrchestrator orchestrator(ChatbotModel model, ToolGateway tools, Clock clock) {
        return new ChatbotOrchestrator(model, tools, TestProperties.withAnthropic(TestProperties.anthropic("http://unused")), clock);
    }

    private ChatbotOrchestrator orchestrator(ChatbotModel model, ToolGateway tools) {
        return orchestrator(model, tools, Clock.fixed(now, ZoneOffset.UTC));
    }

    // ───────────────────────── 테스트 ─────────────────────────

    @Test
    void 도구를_부르고_결과를_돌려준_뒤_답하면_토큰을_모두_합친다() {
        ScriptedModel model = new ScriptedModel().then(tools(100, 10, "t1")).then(answer("커피 이벤트는 없어요.", 200, 30));
        FakeTools tools = new FakeTools();

        ChatbotCompletion completion = orchestrator(model, tools).answer(7L, input, now, deadline);

        assertThat(completion.reply().text()).isEqualTo("커피 이벤트는 없어요.");
        assertThat(completion.inputTokens()).isEqualTo(300);
        assertThat(completion.outputTokens()).isEqualTo(40);
        assertThat(tools.openedFor).as("도구 연결은 질문한 회원으로").isEqualTo(7L);
        assertThat(tools.executed).hasSize(1);
        assertThat(model.results.get(0).get(0).callId()).isEqualTo("t1");
        assertThat(model.offeredTools).extracting(ToolSpec::name).containsExactly("search_events");
        assertThat(tools.closed).isTrue();
    }

    @Test
    void 도구_호출이_한도를_넘으면_실행하지_않고_도구를_끈_채_답하게_한다() {
        ScriptedModel model = new ScriptedModel()
                .then(tools(10, 1, "a1", "a2", "a3"))          // 3회
                .then(tools(10, 1, "b1", "b2", "b3"))          // 2회만 실행, 1개는 한도 초과
                .then(answer("지금까지 찾은 결과예요.", 10, 1));
        FakeTools tools = new FakeTools();

        ChatbotCompletion completion = orchestrator(model, tools).answer(7L, input, now, deadline);

        assertThat(tools.executed).as("최대 5회").hasSize(5);
        ToolOutcome skipped = model.results.get(1).get(2);
        assertThat(skipped.isError()).isTrue();
        assertThat(skipped.text()).isEqualTo(ChatbotOrchestrator.TOOL_LIMIT_MESSAGE);
        assertThat(model.results.get(1)).as("요청한 호출마다 결과가 하나씩 — 짝이 빠지면 API 가 거절한다").hasSize(3);
        assertThat(model.toolsAllowed).containsExactly(true, true, false);
        assertThat(completion.reply().text()).isEqualTo("지금까지 찾은 결과예요.");
    }

    @Test
    void 토큰_상한을_넘으면_다음_호출은_도구_없이() {
        ScriptedModel model = new ScriptedModel().then(tools(19_000, 1_500, "t1")).then(answer("답", 100, 10));

        orchestrator(model, new FakeTools()).answer(7L, input, now, deadline);

        assertThat(model.toolsAllowed).containsExactly(true, false);
    }

    @Test
    void 도구를_끈_호출에서도_도구를_요청하면_INVALID_OUTPUT_이고_쓴_토큰을_보고한다() {
        ScriptedModel model = new ScriptedModel().then(tools(19_000, 1_500, "t1")).then(tools(100, 10, "t2"));

        assertThatThrownBy(() -> orchestrator(model, new FakeTools()).answer(7L, input, now, deadline))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LlmFailureReason.INVALID_OUTPUT);
                    assertThat(e.getInputTokens()).isEqualTo(19_100);
                    assertThat(e.getOutputTokens()).isEqualTo(1_510);
                });
    }

    @Test
    void 마감이_가까우면_새_호출을_시작하지_않는다() {
        ScriptedModel model = new ScriptedModel().then(tools(10, 1, "t1")).then(answer("늦은 답", 10, 1));
        // 첫 호출 때는 여유 있고, 도구를 다녀온 뒤에는 마감 4초 전 (여유 5초 안쪽)
        Deque<Instant> times = new ArrayDeque<>(List.of(now, deadline.minusSeconds(4)));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return times.size() > 1 ? times.poll() : times.peek(); }
        };

        assertThatThrownBy(() -> orchestrator(model, new FakeTools(), clock).answer(7L, input, now, deadline))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LlmFailureReason.LLM_UNAVAILABLE);
                    assertThat(e.getInputTokens()).as("첫 호출 분은 보고").isEqualTo(10);
                });
        assertThat(model.script).as("두 번째 호출은 하지 않음").hasSize(1);
    }

    @Test
    void 중간_호출이_실패하면_앞선_호출의_토큰까지_합쳐_보고한다() {
        ScriptedModel model = new ScriptedModel().then(tools(100, 10, "t1"))
                .thenFail(new LlmCallException(LlmFailureReason.CONTENT_REFUSED, "거절", "m", 50, 5, null));

        assertThatThrownBy(() -> orchestrator(model, new FakeTools()).answer(7L, input, now, deadline))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LlmFailureReason.CONTENT_REFUSED);
                    assertThat(e.getInputTokens()).isEqualTo(150);
                    assertThat(e.getOutputTokens()).isEqualTo(15);
                });
    }

    @Test
    void 빈_답은_INVALID_OUTPUT_긴_답은_계약_길이로_자른다() {
        assertThatThrownBy(() -> orchestrator(new ScriptedModel().then(answer("  ", 1, 1)), new FakeTools())
                .answer(7L, input, now, deadline))
                .isInstanceOfSatisfying(LlmCallException.class,
                        e -> assertThat(e.getReason()).isEqualTo(LlmFailureReason.INVALID_OUTPUT));

        ChatbotCompletion longer = orchestrator(new ScriptedModel().then(answer("가".repeat(3000), 1, 1)), new FakeTools())
                .answer(7L, input, now, deadline);
        assertThat(longer.reply().text()).hasSize(ChatbotReply.TEXT_MAX).endsWith("…");
    }

    @Test
    void 도구_서버에_연결하지_못하면_모델을_부르지_않고_실패한다() {
        ScriptedModel model = new ScriptedModel().then(answer("안 불림", 1, 1));
        ToolGateway down = userId -> {
            throw new LlmCallException(LlmFailureReason.LLM_UNAVAILABLE, "연결 실패");
        };

        assertThatThrownBy(() -> orchestrator(model, down).answer(7L, input, now, deadline))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LlmFailureReason.LLM_UNAVAILABLE);
                    assertThat(e.getInputTokens()).isZero();
                });
        assertThat(model.toolsAllowed).isEmpty();
    }

    @Test
    void 기본_상한은_도구_5회_토큰_20000() {
        var chatbot = TestProperties.chatbot("http://x/mcp", "t");
        assertThat(chatbot.maxToolCalls()).isEqualTo(5);
        assertThat(chatbot.turnTokenCap()).isEqualTo(20_000);
        assertThat(chatbot.deadlineMargin()).isEqualTo(Duration.ofSeconds(5));
    }
}
