package com.couponrush.llm;

import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.couponrush.llm.contract.EventCopyInput;
import com.couponrush.llm.contract.EventCopyTone;
import com.couponrush.llm.contract.LlmTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kafka 발행 실패·한도 초과처럼 실제 브로커로 만들기 어려운 경우를 mock 으로 검증한다 */
class LlmRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T04:30:00Z");

    private final LlmRequestRepository repository = mock(LlmRequestRepository.class);
    private final LlmRateLimiter rateLimiter = mock(LlmRateLimiter.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final LlmProperties properties = new LlmProperties(Duration.ofSeconds(60), Duration.ofMillis(100),
            ZoneId.of("Asia/Seoul"), new LlmProperties.RateLimit(5, 50), new LlmProperties.TimeoutSweep(false, Duration.ofSeconds(30)));
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final LlmRequestService service = new LlmRequestService(repository, rateLimiter, kafkaTemplate, objectMapper,
            properties, Clock.fixed(NOW, ZoneOffset.UTC));

    private final EventCopyInput input = new EventCopyInput("봄맞이", EventCopyTone.FRIENDLY, 10, null, null);

    @Test
    void PENDING_행을_저장한_뒤에_발행한다() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        var accepted = service.requestEventCopy(1L, input, false);

        InOrder order = inOrder(rateLimiter, repository, kafkaTemplate);
        order.verify(rateLimiter).acquire(1L, NOW);
        order.verify(repository).save(any(LlmRequest.class));
        order.verify(kafkaTemplate).send(eq(LlmTopics.REQUESTS), eq(accepted.requestId().toString()), anyString());
        assertThat(accepted.deadlineAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void 발행이_실패하면_FAILED_PUBLISH_FAILED_로_남기고_503() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThatThrownBy(() -> service.requestEventCopy(1L, input, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LLM_REQUEST_UNAVAILABLE);
        verify(repository).fail(any(), eq("PUBLISH_FAILED"), isNull(), eq(0), eq(0), eq(NOW));
    }

    @Test
    void 발행_ack_가_제한_시간_안에_오지_않으면_503() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());   // 영원히 미완료

        assertThatThrownBy(() -> service.requestEventCopy(1L, input, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LLM_REQUEST_UNAVAILABLE);
        verify(repository).fail(any(), eq("PUBLISH_FAILED"), isNull(), anyInt(), anyInt(), any());
    }

    @Test
    void 한도를_넘으면_저장도_발행도_하지_않는다() {
        doThrow(new BusinessException(ErrorCode.LLM_RATE_LIMITED)).when(rateLimiter).acquire(1L, NOW);

        assertThatThrownBy(() -> service.requestEventCopy(1L, input, false)).isInstanceOf(BusinessException.class);

        verify(repository, never()).save(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }
}
