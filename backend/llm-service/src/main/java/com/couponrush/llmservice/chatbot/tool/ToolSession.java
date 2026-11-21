package com.couponrush.llmservice.chatbot.tool;

import java.util.List;

/** 질문 하나 동안 쓰는 도구 서버 연결. 사용자(회원 ID)가 연결에 묶여 있다 — 도구 인자로는 사용자를 바꿀 수 없다 */
public interface ToolSession extends AutoCloseable {

    List<ToolSpec> tools();

    /** 실패해도 예외 대신 isError 결과 (모델이 설명할 수 있게) */
    ToolOutcome call(ToolCall call);

    @Override
    void close();
}
