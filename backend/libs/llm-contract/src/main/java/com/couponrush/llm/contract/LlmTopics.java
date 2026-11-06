package com.couponrush.llm.contract;

/**
 * coupon-api ↔ llm-service Kafka 토픽 이름과 계약 버전.
 * <p>
 * 메시지 키는 두 토픽 모두 {@code requestId.toString()} — 요청끼리 순서가 필요 없으므로 파티션에 고르게 퍼지게 한다.
 * 발급 토픽(coupon-issue-requests)과는 토픽·컨슈머 그룹·리스너 컨테이너를 공유하지 않는다 (발급 경로 격리).
 */
public final class LlmTopics {

    /** coupon-api → llm-service : {@link LlmRequestMessage} */
    public static final String REQUESTS = "llm-requests";

    /** llm-service → coupon-api : {@link LlmResultMessage} */
    public static final String RESULTS = "llm-results";

    /**
     * 역직렬화할 수 없는 요청만 보낸다. LLM 호출 실패는 DLT 가 아니라 FAILED 결과로 돌려준다
     * (재시도할 때마다 비용이 들기 때문).
     */
    public static final String REQUESTS_DLT = REQUESTS + ".DLT";

    /**
     * 메시지 형식 버전. 필드는 <b>추가만</b> 하고(받는 쪽은 모르는 필드를 무시), 이름 변경·삭제·의미 변경이 필요하면 올린다.
     * 받는 쪽은 자기가 모르는 더 높은 버전을 받으면 처리하지 않는다 ({@link #isSupported(int)}).
     */
    public static final int SCHEMA_VERSION = 1;

    public static boolean isSupported(int schemaVersion) {
        return schemaVersion >= 1 && schemaVersion <= SCHEMA_VERSION;
    }

    private LlmTopics() {
    }
}
