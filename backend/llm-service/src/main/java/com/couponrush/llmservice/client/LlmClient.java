package com.couponrush.llmservice.client;

import com.couponrush.llm.contract.EventCopyInput;
import com.couponrush.llm.contract.EventSearchInput;
import com.couponrush.llm.contract.LlmRequestType;

import java.time.LocalDate;

/**
 * LLM 제공자 추상화. 구현: {@link FakeLlmClient}(기본·모든 테스트), Anthropic(키가 있을 때).
 * 처리 흐름(멱등·마감·캐시·예산·결과 발행)은 구현과 무관하게 LlmRequestProcessor 가 맡는다.
 */
public interface LlmClient {

    /**
     * 이벤트 문구 후보 생성.
     *
     * @return 검증을 통과한 후보 1~3개와 사용 토큰
     * @throws LlmCallException 실패 사유가 정해진 실패 (사유는 결과 메시지로 그대로 간다)
     */
    LlmCompletion generateEventCopy(EventCopyInput input);

    /**
     * 자연어 검색 문장 → 검색 조건. 이벤트를 찾는 것이 아니라 <b>조건만</b> 만든다 (조회는 coupon-api 의 DB).
     *
     * @param today "이번 주"·"내일" 같은 상대 날짜의 기준이 되는 한국 날짜 (요청 접수 시각 기준).
     *              시각이 아니라 날짜만 넘긴다 — 같은 날 같은 질문이면 같은 조건이 나와야 캐시할 수 있다
     * @return 계약 규칙(단어 수·길이·범위 순서)을 통과한 조건과 사용 토큰
     * @throws LlmCallException 실패 사유가 정해진 실패
     */
    LlmSearchCompletion extractSearchFilter(EventSearchInput input, LocalDate today);

    /**
     * 응답 캐시 키에 들어가는 "같은 출력을 낼 조건" — 제공자·모델·프롬프트 버전·생성 설정. 기능마다 프롬프트가 다르므로 type 별로.
     * 이 중 하나라도 바뀌면 캐시 키가 달라져 예전 응답을 재사용하지 않는다 (프롬프트를 고치면 캐시가 자연히 무효화).
     */
    String cacheIdentity(LlmRequestType type);
}
