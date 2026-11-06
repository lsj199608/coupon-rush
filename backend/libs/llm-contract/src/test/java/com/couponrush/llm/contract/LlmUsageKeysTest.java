package com.couponrush.llm.contract;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LlmUsageKeysTest {

    @Test
    void 날짜는_한국_시각_기준이다() {
        assertThat(LlmUsageKeys.usageKey(Instant.parse("2026-11-06T14:59:59Z"))).isEqualTo("llm:usage:20261106");   // 23:59:59 KST
        assertThat(LlmUsageKeys.usageKey(Instant.parse("2026-11-06T15:00:00Z"))).isEqualTo("llm:usage:20261107");   // 00:00 KST
    }
}
