package com.couponrush.llmservice.client;

import com.couponrush.llm.contract.EventCopyInput;
import com.couponrush.llm.contract.EventSearchInput;
import com.couponrush.llm.contract.LlmRequestType;

import java.time.LocalDate;

/**
 * LLM 제공자 추상화. 처리 흐름(멱등·마감·캐시·예산·결과 발행)은 구현과 무관하게 LlmRequestProcessor 가 맡고,
 * 구현은 "결과를 누가 만드느냐"만 다르다 (선택은 LlmClientConfig: LLM_PROVIDER, 기본 fake).
 * <ul>
 *   <li><b>fake</b> ({@link FakeLlmClient}, 기본·모든 자동 테스트) — LLM 을 부르지 않고 <b>미리 만들어 둔 코드 규칙</b>으로 결과를 만든다.
 *       문구 생성 = {@link FakeLlmClient#generateEventCopy} 의 톤별 고정 문장, 자연어 검색 = {@link FakeEventSearch} 의 표현 목록·조사/일반어 제거.
 *       비용 0, 항상 같은 결과. 정해진 표현만 알아들으므로 품질이 아니라 흐름 확인용이다.</li>
 *   <li><b>anthropic</b> (client.anthropic.AnthropicLlmClient, LLM_PROVIDER=anthropic + LLM_API_KEY) — Claude 가 문장을 이해해 결과를 만드는
 *       정상 동작. 프롬프트(resources/prompts/*.txt)와 출력 형식(EventCopyOutput, EventSearchOutput)을 주고,
 *       답은 검증기(EventCopyOutputValidator, EventSearchOutputValidator)가 검사한 것만 쓴다.</li>
 * </ul>
 * 어느 구현이든 같은 계약 형식(후보 / EventSearchFilter)을 돌려주므로 coupon-api 는 차이를 모른다.
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
