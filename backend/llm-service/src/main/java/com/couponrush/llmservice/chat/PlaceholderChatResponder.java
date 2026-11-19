package com.couponrush.llmservice.chat;

import com.couponrush.llm.contract.ChatInput;
import com.couponrush.llm.contract.ChatReply;
import org.springframework.stereotype.Component;

/**
 * <b>임시</b> 답변 (7단계 C1). 질문 → Kafka → 여기 → 결과 → coupon-api 대화 기록까지 흐름을 확인하기 위한 것으로,
 * LLM·도구를 부르지 않는다 (비용 0, 제공자 설정과 무관). C2 에서 ChatOrchestrator 로 바꾼다.
 */
@Component
public class PlaceholderChatResponder implements ChatResponder {

    public static final String MODEL = "placeholder";

    @Override
    public ChatCompletion answer(long userId, ChatInput input) {
        String text = "아직 챗봇을 준비하고 있어요. 받은 질문: \"" + input.message() + "\" (이전 대화 "
                + input.history().size() + "개). 이벤트 찾기는 목록 화면의 검색창을 이용해 주세요.";
        return new ChatCompletion(new ChatReply(text), MODEL, 0, 0);
    }
}
