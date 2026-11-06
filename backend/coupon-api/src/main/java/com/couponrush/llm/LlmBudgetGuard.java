package com.couponrush.llm;

import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.couponrush.llm.contract.LlmUsageKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 오늘 LLM 토큰 예산이 이미 바닥났으면 접수하지 않는다 (빠른 실패).
 * 사용량은 llm-service 가 llm:usage:{yyyyMMdd} 에 기록한다 — 키 규칙은 계약 모듈(LlmUsageKeys)에 있어 두 서비스가 같다.
 * <p>
 * 최종 판정은 llm-service 다 (이미 접수된 요청이 처리되는 사이에 예산이 바닥날 수 있으므로 → 그 요청은 FAILED(BUDGET_EXCEEDED)).
 * 여기서 먼저 막으면: 관리자가 202 → 폴링 → 실패를 기다리지 않고 바로 안내를 보고, 행·메시지·레이트 리밋 횟수도 쓰지 않는다.
 */
@Component
public class LlmBudgetGuard {

    private final StringRedisTemplate redisTemplate;
    private final LlmProperties properties;

    public LlmBudgetGuard(StringRedisTemplate redisTemplate, LlmProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void checkRemaining(Instant now) {
        Object used = redisTemplate.opsForHash().get(LlmUsageKeys.usageKey(now), LlmUsageKeys.TOTAL_TOKENS);
        if (used != null && Long.parseLong(used.toString()) >= properties.dailyTokenBudget()) {
            throw new BusinessException(ErrorCode.LLM_BUDGET_EXCEEDED);
        }
    }
}
