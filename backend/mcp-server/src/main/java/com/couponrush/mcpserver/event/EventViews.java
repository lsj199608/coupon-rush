package com.couponrush.mcpserver.event;

import java.util.List;

/**
 * 도구 결과 본문(JSON)의 모양. 모델이 필드를 정확히 읽을 수 있게 이름·단위를 고정한다 (시각은 한국 시각 문자열, 발급은 url 에서 사람이).
 */
public final class EventViews {

    private EventViews() {
    }

    /**
     * @param status      UPCOMING / OPEN / ENDED (statusLabel 은 한국어)
     * @param issueStart  발급 시작 (한국 시각, 포함)
     * @param issueEnd    발급 종료 (한국 시각, 이 시각부터는 발급 안 됨)
     * @param url         이벤트 페이지 — 발급은 여기서 사용자가 직접
     */
    public record EventView(long id, String name, String description, String status, String statusLabel,
                            int remainingQuantity, int totalQuantity, String issueStart, String issueEnd, String url) {
    }

    /**
     * @param searchMethod   AI_FILTER = AI 가 만든 조건으로 찾음 / KEYWORD = AI 를 못 써서 질문 속 단어 중 하나라도 들어 있는 이벤트
     * @param conditions     AI_FILTER 일 때 쓴 조건 (기간은 [from, to), UTC)
     * @param keywords       KEYWORD 일 때 쓴 단어
     * @param fallbackReason KEYWORD 가 된 이유 (RATE_LIMITED, BUDGET_EXCEEDED, TIMEOUT, LLM_UNAVAILABLE)
     */
    public record SearchView(String query, String searchMethod, Object conditions, List<String> keywords,
                             String fallbackReason, int count, List<EventView> events) {
    }

    public record MyCouponsView(int count, List<MyCouponView> coupons) {
    }

    /**
     * @param issuedAt 받은 시각 (한국 시각)
     * @param url      이벤트 페이지
     */
    public record MyCouponView(long issueId, long eventId, String eventName, String issuedAt, String url) {
    }
}
