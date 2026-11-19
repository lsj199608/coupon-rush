package com.couponrush.llmservice.chat;

import com.couponrush.llm.contract.ChatInput;
import com.couponrush.llm.contract.ChatReply;
import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llmservice.LlmServiceProperties;
import com.couponrush.llmservice.chat.model.ChatConversation;
import com.couponrush.llmservice.chat.model.ChatModel;
import com.couponrush.llmservice.chat.model.ModelTurn;
import com.couponrush.llmservice.chat.tool.ToolCall;
import com.couponrush.llmservice.chat.tool.ToolGateway;
import com.couponrush.llmservice.chat.tool.ToolOutcome;
import com.couponrush.llmservice.chat.tool.ToolSession;
import com.couponrush.llmservice.client.KstDates;
import com.couponrush.llmservice.client.LlmCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 챗봇 질문 하나 = 단일 에이전트 tool use 루프 (7단계 C2).
 * <pre>
 * 도구 서버 연결(회원 고정) → 모델 호출 ─┬ 답 → 끝
 *                                     └ 도구 호출 요청 → mcp-server 로 실행 → 결과를 모델에게 → 다시 모델 호출 …
 * </pre>
 * 상한 (질문 하나 기준) — 넘으면 끊지 않고 "도구 없이 지금까지 결과로 답하라"고 한 번 더 부른다 (답 없이 비용만 남기지 않게):
 * <ul>
 *   <li>도구 호출 최대 {@code maxToolCalls}회 — 넘는 호출은 실행하지 않고 "한도 초과" 결과로 돌려준다</li>
 *   <li>토큰 {@code turnTokenCap} — 넘으면 다음 호출은 도구 없이. 실제 최대치는 상한 + 호출 1회분</li>
 *   <li>마감 — {@code deadlineMargin} 안쪽이면 새 모델 호출을 시작하지 않고 실패 (결과가 어차피 버려지므로)</li>
 * </ul>
 * <b>회원은 연결에 묶여 있다</b> — ToolGateway.open(userId) 의 헤더로만 정해지고, 모델이 도구 인자로 바꿀 수 없다
 * (get_my_coupons 는 인자가 없다). 도구는 전부 조회 전용이라 모델이 속아도 할 수 있는 일은 조회뿐이다.
 * 일일 예산·사용량 기록·멱등은 이 바깥(LlmRequestProcessor)에서 다른 기능과 똑같이 한다.
 */
@Component
public class ChatOrchestrator implements ChatResponder {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    static final String TOOL_LIMIT_MESSAGE = "도구 호출 한도에 닿아 실행하지 않았습니다. 지금까지의 결과로 답해 주세요.";

    private final ChatModel model;
    private final ToolGateway tools;
    private final LlmServiceProperties.Chat config;
    private final Clock clock;

    public ChatOrchestrator(ChatModel model, ToolGateway tools, LlmServiceProperties properties, Clock clock) {
        this.model = model;
        this.tools = tools;
        this.config = properties.chat();
        this.clock = clock;
    }

    @Override
    public ChatCompletion answer(long userId, ChatInput input, Instant requestedAt, Instant deadline) {
        Usage usage = new Usage();
        try (ToolSession session = tools.open(userId)) {
            ChatConversation conversation = model.start(input, session.tools(), KstDates.dateOf(requestedAt));
            int toolCalls = 0;
            boolean toolsAllowed = true;
            while (true) {
                if (!clock.instant().plus(config.deadlineMargin()).isBefore(deadline)) {
                    throw usage.failure(LlmFailureReason.LLM_UNAVAILABLE, "마감이 가까워 더 진행하지 않음 (도구 " + toolCalls + "회)");
                }
                toolsAllowed = toolsAllowed && toolCalls < config.maxToolCalls() && usage.total() < config.turnTokenCap();

                ModelTurn turn = usage.add(conversation, toolsAllowed);
                if (turn instanceof ModelTurn.Answer answer) {
                    return usage.completion(reply(answer.text(), usage));
                }

                ModelTurn.ToolRequests requests = (ModelTurn.ToolRequests) turn;
                if (!toolsAllowed) {
                    // 도구를 끄고 불렀는데도 도구를 요청 — 더 돌리면 상한이 의미 없어진다
                    throw usage.failure(LlmFailureReason.INVALID_OUTPUT, "도구를 끈 마지막 호출에서도 도구를 요청함");
                }
                List<ToolOutcome> outcomes = new ArrayList<>();
                for (ToolCall call : requests.calls()) {
                    if (toolCalls >= config.maxToolCalls()) {
                        outcomes.add(ToolOutcome.error(call.id(), TOOL_LIMIT_MESSAGE));
                        continue;
                    }
                    toolCalls++;
                    log.info("챗봇 도구 호출 {}/{}: tool={}", toolCalls, config.maxToolCalls(), call.name());
                    outcomes.add(session.call(call));
                }
                conversation.addToolResults(outcomes);
            }
        } catch (LlmCallException e) {
            throw usage.merge(e);
        }
    }

    /** 답 정리: 앞뒤 공백 제거, 계약 길이로 자름. 빈 답은 출력 오류 */
    private static ChatReply reply(String text, Usage usage) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            throw usage.failure(LlmFailureReason.INVALID_OUTPUT, "빈 답");
        }
        return new ChatReply(trimmed.length() <= ChatReply.TEXT_MAX ? trimmed : trimmed.substring(0, ChatReply.TEXT_MAX - 1) + "…");
    }

    /** 루프가 직접 정한 실패 (이미 질문 전체의 토큰을 담고 있다) */
    private static final class TurnFailure extends LlmCallException {
        TurnFailure(LlmFailureReason reason, String message, String model, int inputTokens, int outputTokens) {
            super(reason, message, model, inputTokens, outputTokens, null);
        }
    }

    /** 질문 하나의 사용량 (모든 모델 호출 합) — 실패해도 쓴 만큼 보고해서 예산에 잡히게 */
    private static final class Usage {
        private String model;
        private int inputTokens;
        private int outputTokens;

        ModelTurn add(ChatConversation conversation, boolean toolsAllowed) {
            ModelTurn turn = conversation.next(toolsAllowed);
            model = turn.model();
            inputTokens += turn.inputTokens();
            outputTokens += turn.outputTokens();
            return turn;
        }

        int total() {
            return inputTokens + outputTokens;
        }

        ChatCompletion completion(ChatReply reply) {
            return new ChatCompletion(reply, model, inputTokens, outputTokens);
        }

        LlmCallException failure(LlmFailureReason reason, String message) {
            return new TurnFailure(reason, message, model, inputTokens, outputTokens);
        }

        /** 모델 호출·도구 연결 중 실패: 그 호출의 토큰(예외에 담김) + 앞선 호출들의 토큰 */
        LlmCallException merge(LlmCallException e) {
            if (e instanceof TurnFailure) {
                return e;   // 이미 질문 전체 사용량으로 만든 예외
            }
            return new LlmCallException(e.getReason(), e.getMessage(), e.getModel() != null ? e.getModel() : model,
                    inputTokens + e.getInputTokens(), outputTokens + e.getOutputTokens(), e);
        }
    }
}
