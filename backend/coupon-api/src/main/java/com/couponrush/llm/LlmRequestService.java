package com.couponrush.llm;

import com.couponrush.global.error.BusinessException;
import com.couponrush.global.error.ErrorCode;
import com.couponrush.llm.contract.EventCopyCandidate;
import com.couponrush.llm.contract.EventCopyInput;
import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llm.contract.LlmRequestMessage;
import com.couponrush.llm.contract.LlmRequestType;
import com.couponrush.llm.contract.LlmTopics;
import com.couponrush.llm.dto.LlmRequestAcceptedResponse;
import com.couponrush.llm.dto.LlmRequestResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * [LLM 요청 — coupon-api 쪽] 한도 확인 → llm_requests PENDING 저장 → llm-requests 발행 → 202.
 * LLM 은 여기서 부르지 않는다 (llm-service 담당). 발급 경로(coupon 패키지)와 코드를 공유하지 않는다.
 * <p>
 * 순서가 중요하다:
 * <ol>
 *   <li>한도 초과면 아무것도 만들지 않고 429</li>
 *   <li>PENDING 행을 <b>먼저 커밋</b> — 발행 후에 저장하면 결과가 행보다 먼저 도착해 조건부 UPDATE 가 0행이 되고 결과를 잃는다</li>
 *   <li>발행 후 브로커 ack 대기. 실패하면 FAILED(PUBLISH_FAILED) + 503</li>
 * </ol>
 * 이 메서드들은 @Transactional 이 아니다 — 각 저장·UPDATE 가 바로 커밋되어야 하고, 조회도 매번 DB 의 최신 상태를 읽어야 한다.
 */
@Service
public class LlmRequestService {

    private static final Logger log = LoggerFactory.getLogger(LlmRequestService.class);
    private static final TypeReference<List<EventCopyCandidate>> CANDIDATES = new TypeReference<>() {
    };

    private final LlmRequestRepository repository;
    private final LlmRateLimiter rateLimiter;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final LlmProperties properties;
    private final Clock clock;

    public LlmRequestService(LlmRequestRepository repository, LlmRateLimiter rateLimiter,
                             KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                             LlmProperties properties, Clock clock) {
        this.repository = repository;
        this.rateLimiter = rateLimiter;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public LlmRequestAcceptedResponse requestEventCopy(Long userId, EventCopyInput input, boolean regenerate) {
        // PostgreSQL TIMESTAMP 는 마이크로초까지 (Windows 시계는 100ns). 잘라 두지 않으면 메시지의 deadline 과 DB 의 deadline_at 이 미세하게 달라진다
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        rateLimiter.acquire(userId, now);

        UUID requestId = UUID.randomUUID();
        Instant deadline = now.plus(properties.requestTimeout());
        LlmRequestMessage message = LlmRequestMessage.eventCopy(requestId, userId, now, deadline, regenerate, input);

        repository.save(LlmRequest.pending(requestId, LlmRequestType.EVENT_COPY, userId, toJson(input), now, deadline));
        publish(message);
        return LlmRequestAcceptedResponse.pending(requestId, deadline);
    }

    /**
     * 본인 요청만 (남의 requestId 는 존재 여부도 숨긴다 → 404, 발급 요청 조회와 같은 규칙).
     * 마감이 지난 PENDING 은 이 자리에서 TIMEOUT 으로 확정한다 — 스케줄러(30초 주기)를 기다리면 화면이 그만큼 더 PENDING 을 본다.
     */
    public LlmRequestResponse get(UUID requestId, Long userId) {
        LlmRequest request = findOwned(requestId, userId);
        Instant now = clock.instant();
        if (request.isExpiredPending(now) && repository.timeoutIfExpired(requestId, now) == 1) {
            request = findOwned(requestId, userId);
        }
        return LlmRequestResponse.of(request, candidatesOf(request));
    }

    private LlmRequest findOwned(UUID requestId, Long userId) {
        return repository.findById(requestId)
                .filter(request -> request.isRequestedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.LLM_REQUEST_NOT_FOUND));
    }

    private void publish(LlmRequestMessage message) {
        try {
            kafkaTemplate.send(LlmTopics.REQUESTS, message.requestId().toString(), toJson(message))
                    .get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw publishFailed(message, e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw publishFailed(message, e);
        }
    }

    /**
     * 타임아웃은 "실제로는 기록됐을 수도" 있다. 그러면 llm-service 가 처리해 결과를 보내지만,
     * 행이 이미 FAILED 라 조건부 UPDATE 가 0행 → 무시된다 (그 호출 비용은 나간다 — 드문 경우라 허용).
     */
    private BusinessException publishFailed(LlmRequestMessage message, Exception cause) {
        log.warn("LLM 요청 발행 실패: requestId={}", message.requestId(), cause);
        try {
            repository.fail(message.requestId(), LlmFailureReason.PUBLISH_FAILED.name(), null, 0, 0, clock.instant());
        } catch (RuntimeException e) {
            log.error("발행 실패 기록 실패: requestId={} (마감 후 TIMEOUT 으로 정리됨)", message.requestId(), e);
        }
        return new BusinessException(ErrorCode.LLM_REQUEST_UNAVAILABLE);
    }

    private List<EventCopyCandidate> candidatesOf(LlmRequest request) {
        if (request.getStatus() != LlmRequestStatus.COMPLETED || request.getResult() == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(request.getResult(), CANDIDATES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 LLM 결과를 읽을 수 없습니다: requestId=" + request.getRequestId(), e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("LLM 요청 직렬화 실패", e);
        }
    }
}
