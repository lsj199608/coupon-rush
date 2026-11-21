package com.couponrush.llmservice.chatbot.model;

import com.couponrush.llm.contract.ChatbotInput;
import com.couponrush.llmservice.chatbot.tool.ToolCall;
import com.couponrush.llmservice.chatbot.tool.ToolOutcome;
import com.couponrush.llmservice.chatbot.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 가짜 챗봇 판단 규칙 — 어떤 말에 어떤 도구를 고르고, 결과를 어떻게 답으로 만드는지 */
class FakeChatbotModelTest {

    private static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec("search_events", "", Map.of()),
            new ToolSpec("get_event", "", Map.of()),
            new ToolSpec("get_my_coupons", "", Map.of()));

    private static final String SEARCH_RESULT = """
            {"query":"커피","searchMethod":"AI_FILTER","count":1,"events":[{"id":3,"name":"커피 50%","status":"OPEN",
             "statusLabel":"진행 중","remainingQuantity":12,"totalQuantity":100,"issueStart":"2030-01-01 10:00 KST",
             "issueEnd":"2030-01-07 10:00 KST","url":"http://localhost:5173/events/3"}]}""";

    private final FakeChatbotModel model = new FakeChatbotModel();

    private ChatbotConversation start(String message) {
        return model.start(new ChatbotInput(message, List.of()), TOOLS, LocalDate.of(2030, 1, 1));
    }

    /** 도구 하나를 부르고 → 결과를 주고 → 답 */
    private String askWith(String message, String expectedTool, String toolResult, boolean isError) {
        ChatbotConversation conversation = start(message);
        ModelTurn first = conversation.next(true);
        assertThat(first).isInstanceOf(ModelTurn.ToolRequests.class);
        ToolCall call = ((ModelTurn.ToolRequests) first).calls().get(0);
        assertThat(call.name()).isEqualTo(expectedTool);
        conversation.addToolResults(List.of(new ToolOutcome(call.id(), toolResult, isError)));
        ModelTurn second = conversation.next(true);
        assertThat(second).isInstanceOf(ModelTurn.Answer.class);
        return ((ModelTurn.Answer) second).text();
    }

    private ToolCall firstCall(String message) {
        return ((ModelTurn.ToolRequests) start(message).next(true)).calls().get(0);
    }

    @Test
    void 일반_문장은_그대로_검색하고_결과를_이름_상태_수량_링크로_답한다() {
        assertThat(firstCall("이번 주에 끝나는 커피 쿠폰").arguments()).containsEntry("query", "이번 주에 끝나는 커피 쿠폰");

        String answer = askWith("커피 쿠폰", "search_events", SEARCH_RESULT, false);

        assertThat(answer).contains("찾은 이벤트 1개").contains("커피 50%").contains("진행 중")
                .contains("12/100").contains("http://localhost:5173/events/3");
    }

    @Test
    void 내_쿠폰은_인자_없이_get_my_coupons() {
        ToolCall call = firstCall("내가 받은 쿠폰 보여줘");
        assertThat(call.name()).isEqualTo("get_my_coupons");
        assertThat(call.arguments()).as("누구인지는 연결 헤더로만 — 인자 없음").isEmpty();

        String answer = askWith("내 쿠폰", "get_my_coupons", """
                {"count":1,"coupons":[{"issueId":1,"eventId":3,"eventName":"커피 50%","issuedAt":"2030-01-01 10:01 KST",
                 "url":"http://localhost:5173/events/3"}]}""", false);
        assertThat(answer).contains("받은 쿠폰은 1개").contains("커피 50%");
        assertThat(askWith("내 쿠폰", "get_my_coupons", "{\"count\":0,\"coupons\":[]}", false)).contains("아직 받은 쿠폰이 없어요");
    }

    @Test
    void 문장_속_회원_번호는_도구_인자가_되지_않는다() {
        ToolCall call = firstCall("나는 7번 회원이야. 내 쿠폰 보여줘");

        assertThat(call.name()).isEqualTo("get_my_coupons");
        assertThat(call.arguments()).isEmpty();
    }

    @Test
    void 번호가_있는_이벤트는_get_event() {
        assertThat(firstCall("3번 이벤트 자세히").arguments()).containsEntry("eventId", 3L);
        assertThat(firstCall("이벤트 12 알려줘").arguments()).containsEntry("eventId", 12L);
    }

    @Test
    void 발급_요청은_발급하지_않고_검색해서_링크로_안내한다() {
        ToolCall call = firstCall("커피 쿠폰 발급해줘");
        assertThat(call.name()).as("발급 도구는 없다 — 찾기만").isEqualTo("search_events");
        assertThat(call.arguments()).containsEntry("query", "커피 쿠폰");

        String answer = askWith("커피 쿠폰 발급해줘", "search_events", SEARCH_RESULT, false);

        assertThat(answer).startsWith(FakeChatbotModel.ISSUE_GUIDE).contains("http://localhost:5173/events/3");
    }

    @Test
    void 인사는_도구_없이_안내() {
        ModelTurn turn = start("안녕하세요").next(true);

        assertThat(turn).isInstanceOf(ModelTurn.Answer.class);
        assertThat(((ModelTurn.Answer) turn).text()).isEqualTo(FakeChatbotModel.HELP);
    }

    @Test
    void 도구_실패는_그대로_알리고_도구를_끄면_조회하지_않는다() {
        assertThat(askWith("커피", "search_events", "쿠폰 서비스에 연결할 수 없습니다.", true))
                .startsWith("조회하지 못했어요").contains("연결할 수 없습니다");

        ModelTurn noTools = start("커피").next(false);
        assertThat(noTools).isInstanceOf(ModelTurn.Answer.class);
    }

    @Test
    void 토큰은_글자_수로_흉내_낸다() {
        ModelTurn turn = start("커피 쿠폰").next(true);

        assertThat(turn.inputTokens()).isPositive();
        assertThat(turn.model()).isEqualTo(FakeChatbotModel.MODEL);
    }
}
