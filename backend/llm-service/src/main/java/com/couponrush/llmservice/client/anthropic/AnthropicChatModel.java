package com.couponrush.llmservice.client.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.couponrush.llm.contract.ChatInput;
import com.couponrush.llm.contract.ChatTurn;
import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llmservice.LlmServiceProperties;
import com.couponrush.llmservice.chat.model.ChatConversation;
import com.couponrush.llmservice.chat.model.ChatModel;
import com.couponrush.llmservice.chat.model.ModelTurn;
import com.couponrush.llmservice.chat.tool.ToolCall;
import com.couponrush.llmservice.chat.tool.ToolOutcome;
import com.couponrush.llmservice.chat.tool.ToolSpec;
import com.couponrush.llmservice.client.LlmCallException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Claude tool use — 수동 루프의 "모델 호출" 부분 (루프 자체는 ChatOrchestrator). Messages API, 공식 Java SDK.
 * <ul>
 *   <li>도구: mcp-server 의 tools/list 결과(이름·설명·JSON Schema)를 그대로 Claude 도구 정의로 넘긴다</li>
 *   <li>대화: 응답을 {@code Message.toParam()} 으로 <b>그대로</b> 이어 붙인다 — 생각(thinking) 블록을 지우거나 고치면
 *       다음 호출에서 그 생각이 무효가 된다 (append-only). 도구 결과는 한 user 메시지에 모두 담는다 (병렬 호출 짝 유지)</li>
 *   <li>도구를 끌 때: tool_choice none — 도구 정의는 그대로 둔다 (앞선 tool_use 블록이 대화에 남아 있으므로)</li>
 *   <li>생각: thinking 은 보내지 않는다(적응형 기본), 깊이는 effort 로. 출력 상한 app.llm.chat.max-tokens</li>
 *   <li>거절(refusal) → CONTENT_REFUSED, max_tokens 잘림 → INVALID_OUTPUT. 재시도·서킷 브레이커는 AnthropicLlmClient 와 공유</li>
 * </ul>
 */
public class AnthropicChatModel implements ChatModel {

    static final String PROMPT_VERSION = "chat.v1";

    private final AnthropicLlmClient client;
    private final LlmServiceProperties.Anthropic config;
    private final long maxTokens;
    private final String systemPrompt;

    public AnthropicChatModel(AnthropicLlmClient client, LlmServiceProperties.Chat chat) {
        this.client = client;
        this.config = client.config();
        this.maxTokens = chat.maxTokens();
        this.systemPrompt = loadPrompt();
    }

    @Override
    public ChatConversation start(ChatInput input, List<ToolSpec> tools, LocalDate today) {
        return new Conversation(input, tools.stream().map(AnthropicChatModel::toTool).toList(), today);
    }

    final class Conversation implements ChatConversation {

        private final List<Tool> tools;
        private final String system;
        private final List<MessageParam> messages = new ArrayList<>();

        Conversation(ChatInput input, List<Tool> tools, LocalDate today) {
            this.tools = tools;
            this.system = systemPrompt + "\n오늘은 " + today + " ("
                    + today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN) + "), 한국 시각 기준입니다.";
            for (ChatTurn turn : input.history()) {
                messages.add(MessageParam.ofUser(turn.question()));
                messages.add(MessageParam.ofAssistant(turn.answer()));
            }
            messages.add(MessageParam.ofUser(input.message()));
        }

        @Override
        public ModelTurn next(boolean toolsAllowed) {
            Message message = client.createMessage(params(toolsAllowed), config.model(), 0, 0);
            String model = message.model().asString();   // 서버 측 fallback 이면 실제로 답한 모델
            int inputTokens = (int) message.usage().inputTokens();
            int outputTokens = (int) message.usage().outputTokens();

            Optional<StopReason> stop = message.stopReason();
            if (stop.isPresent() && stop.get().equals(StopReason.REFUSAL)) {
                String category = message.stopDetails().flatMap(d -> d.category()).map(Object::toString).orElse("unknown");
                throw new LlmCallException(LlmFailureReason.CONTENT_REFUSED, "모델이 답을 거절함: category=" + category,
                        model, inputTokens, outputTokens, null);
            }
            if (stop.isPresent() && stop.get().equals(StopReason.MAX_TOKENS)) {
                throw new LlmCallException(LlmFailureReason.INVALID_OUTPUT, "max_tokens(" + maxTokens + ")에서 잘림",
                        model, inputTokens, outputTokens, null);
            }

            messages.add(message.toParam());
            List<ToolCall> calls = message.content().stream()
                    .flatMap(block -> block.toolUse().stream())
                    .map(AnthropicChatModel::toCall)
                    .toList();
            if (!calls.isEmpty()) {
                return new ModelTurn.ToolRequests(calls, model, inputTokens, outputTokens);
            }
            String text = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(TextBlock::text)
                    .collect(Collectors.joining("\n"));
            return new ModelTurn.Answer(text, model, inputTokens, outputTokens);
        }

        @Override
        public void addToolResults(List<ToolOutcome> results) {
            messages.add(MessageParam.ofUserOfContentBlockParams(results.stream()
                    .map(result -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(result.callId())
                            .content(result.text())
                            .isError(result.isError())
                            .build()))
                    .toList()));
        }

        MessageCreateParams params(boolean toolsAllowed) {
            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .model(config.model())
                    .maxTokens(maxTokens)
                    .system(system)
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(config.effort())).build())
                    .messages(messages);
            tools.forEach(builder::addTool);
            if (!tools.isEmpty()) {
                if (toolsAllowed) {
                    builder.toolChoice(ToolChoiceAuto.builder().build());
                } else {
                    builder.toolChoice(ToolChoiceNone.builder().build());
                }
            }
            if (config.fallbacks()) {
                builder.putAdditionalHeader("anthropic-beta", AnthropicLlmClient.FALLBACK_BETA)
                        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
            }
            return builder.build();
        }
    }

    /** MCP 도구 정의(JSON Schema) → Claude 도구. 스키마의 properties·required·additionalProperties 를 그대로 옮긴다 */
    @SuppressWarnings("unchecked")
    static Tool toTool(ToolSpec spec) {
        Map<String, Object> schema = spec.inputSchema() == null ? Map.of() : spec.inputSchema();
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        if (schema.get("properties") instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).forEach((name, value) -> properties.putAdditionalProperty(name, JsonValue.from(value)));
        }
        Tool.InputSchema.Builder input = Tool.InputSchema.builder().properties(properties.build());
        if (schema.get("required") instanceof List<?> required && !required.isEmpty()) {
            input.required(required.stream().map(Object::toString).toList());
        }
        if (schema.containsKey("additionalProperties")) {
            input.putAdditionalProperty("additionalProperties", JsonValue.from(schema.get("additionalProperties")));
        }
        Tool.Builder tool = Tool.builder().name(spec.name()).inputSchema(input.build());
        if (spec.description() != null && !spec.description().isBlank()) {
            tool.description(spec.description());
        }
        return tool.build();
    }

    private static ToolCall toCall(ToolUseBlock block) {
        Map<String, Object> arguments = block._input().convert(new TypeReference<Map<String, Object>>() {
        });
        return new ToolCall(block.id(), block.name(), arguments);
    }

    private static String loadPrompt() {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource("prompts/" + PROMPT_VERSION + ".txt").getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
