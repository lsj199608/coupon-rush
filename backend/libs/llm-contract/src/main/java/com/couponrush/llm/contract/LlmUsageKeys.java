package com.couponrush.llm.contract;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 하루 LLM 사용량(Redis hash) 키 규칙 — 두 서비스가 같은 키를 쓴다.
 * <ul>
 *   <li>llm-service: 호출할 때마다 실제 사용 토큰을 더하고(HINCRBY), 예산을 넘었으면 LLM 을 부르지 않는다 (최종 판정)</li>
 *   <li>coupon-api: 이미 넘었으면 접수 단계에서 바로 거절한다 (빠른 실패 — 행·메시지·레이트 리밋 횟수를 쓰지 않게)</li>
 * </ul>
 * 키: {@code llm:usage:{yyyyMMdd}} (한국 시각 기준 날짜 → 예산은 한국 자정에 초기화), TTL 8일 (일주일 사용량을 볼 수 있게).
 */
public final class LlmUsageKeys {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 예산 판정 기준: 입력 + 출력 토큰 합계 */
    public static final String TOTAL_TOKENS = "total_tokens";
    public static final String INPUT_TOKENS = "input_tokens";
    public static final String OUTPUT_TOKENS = "output_tokens";
    /** 실제 LLM 호출 수 (재생성 포함 1건으로 셈 — 요청 단위) */
    public static final String CALLS = "calls";
    /** 응답 캐시로 처리해 호출하지 않은 수 */
    public static final String CACHE_HITS = "cache_hits";
    /** 예산 초과로 호출하지 않은 수 */
    public static final String BUDGET_REJECTIONS = "budget_rejections";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private LlmUsageKeys() {
    }

    public static String usageKey(Instant now) {
        return "llm:usage:" + DAY.format(now.atZone(ZONE));
    }
}
