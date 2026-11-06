package com.couponrush.llmservice.client;

import com.couponrush.llm.contract.EventCopyInput;

/**
 * LLM 제공자 추상화. 구현: {@link FakeLlmClient}(기본·모든 테스트), (4단계) Anthropic(Spring AI).
 * 처리 흐름(멱등·마감·결과 발행)은 구현과 무관하게 LlmRequestProcessor 가 맡는다.
 */
public interface LlmClient {

    /**
     * 이벤트 문구 후보 생성.
     *
     * @return 검증을 통과한 후보 1~3개와 사용 토큰
     * @throws LlmCallException 실패 사유가 정해진 실패 (사유는 결과 메시지로 그대로 간다)
     */
    LlmCompletion generateEventCopy(EventCopyInput input);
}
