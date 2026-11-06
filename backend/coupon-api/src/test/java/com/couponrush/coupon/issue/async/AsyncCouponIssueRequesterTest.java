package com.couponrush.coupon.issue.async;

import com.couponrush.coupon.issue.redis.RedisCouponStock;
import com.couponrush.coupon.issue.redis.RedisIssueRejections;
import com.couponrush.coupon.issue.redis.RedisIssueResult;
import com.couponrush.coupon.request.PendingIssueRequestStore;
import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Kafka 발행 실패처럼 실제 브로커로는 만들기 어려운 경우를 mock 으로 검증한다 */
class AsyncCouponIssueRequesterTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    private final RedisCouponStock stock = mock(RedisCouponStock.class);
    private final RedisIssueRejections rejections = mock(RedisIssueRejections.class);
    private final PendingIssueRequestStore pendingStore = mock(PendingIssueRequestStore.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final AsyncCouponIssueRequester requester = new AsyncCouponIssueRequester(stock, rejections, pendingStore,
            kafkaTemplate, new ObjectMapper().registerModule(new JavaTimeModule()), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 성공하면_쿠폰_ID_를_키로_발행하고_requestId_를_돌려준다() {
        given(stock.tryIssue(1L, 10L, NOW)).willReturn(RedisIssueResult.SUCCESS);
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        String requestId = requester.request(1L, 10L);

        assertThat(requestId).hasSize(36);
        verify(pendingStore).save(requestId, 1L, 10L, NOW);
        verify(kafkaTemplate).send(eq("coupon-issue-requests"), eq("1"), org.mockito.ArgumentMatchers.contains(requestId));
    }

    @Test
    void Kafka_발행이_실패하면_Redis_를_되돌리고_503() {
        given(stock.tryIssue(1L, 10L, NOW)).willReturn(RedisIssueResult.SUCCESS);
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThatThrownBy(() -> requester.request(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_ISSUE_UNAVAILABLE);

        verify(stock).restore(eq(1L), eq(10L), eq(true), anyString());   // 재고 복구 + Set 에서 제거 (requestId 로 멱등)
        verify(pendingStore).delete(anyString());   // PENDING 도 지운다
    }

    @Test
    void Kafka_send_호출_자체가_예외를_던져도_되돌린다() {
        given(stock.tryIssue(1L, 10L, NOW)).willReturn(RedisIssueResult.SUCCESS);
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willThrow(new RuntimeException("metadata timeout"));

        assertThatThrownBy(() -> requester.request(1L, 10L)).isInstanceOf(BusinessException.class);

        verify(stock).restore(eq(1L), eq(10L), eq(true), anyString());
    }

    @Test
    void Redis_판정에서_떨어지면_발행하지_않는다() {
        given(stock.tryIssue(1L, 10L, NOW)).willReturn(RedisIssueResult.SOLD_OUT);
        given(rejections.toException(RedisIssueResult.SOLD_OUT, 1L, NOW))
                .willReturn(new BusinessException(ErrorCode.COUPON_SOLD_OUT));

        assertThatThrownBy(() -> requester.request(1L, 10L)).isInstanceOf(BusinessException.class);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(pendingStore, never()).save(anyString(), any(), any(), any());
    }
}
