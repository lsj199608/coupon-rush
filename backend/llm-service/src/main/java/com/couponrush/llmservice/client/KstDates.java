package com.couponrush.llmservice.client;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * 검색 조건의 날짜 계산 (한국 날짜 기준). 범위는 모두 날짜 경계(0시)로 끊는다 — "지금" 같은 시각을 넣으면
 * 같은 날 같은 질문의 조건이 매번 달라져 캐시할 수 없고, 결과 차이도 거의 없다 ("지금 진행 중"은 상태 조건 OPEN 으로 표현).
 */
public final class KstDates {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private KstDates() {
    }

    /** 그 날 0시(KST) */
    public static Instant startOf(LocalDate date) {
        return date.atStartOfDay(KST).toInstant();
    }

    public static LocalDate dateOf(Instant instant) {
        return instant.atZone(KST).toLocalDate();
    }

    /** 그 주 월요일 (한국 달력은 월요일 시작으로 본다) */
    public static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
