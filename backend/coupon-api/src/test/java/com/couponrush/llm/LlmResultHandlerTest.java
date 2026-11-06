package com.couponrush.llm;

import com.couponrush.IntegrationTest;
import com.couponrush.llm.contract.EventCopyCandidate;
import com.couponrush.llm.contract.LlmFailureReason;
import com.couponrush.llm.contract.LlmRequestType;
import com.couponrush.llm.contract.LlmResultMessage;
import com.couponrush.llm.contract.LlmResultStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.couponrush.llm.LlmResultHandler.Outcome.APPLIED;
import static com.couponrush.llm.LlmResultHandler.Outcome.IGNORED_ALREADY_FINISHED;
import static com.couponrush.llm.LlmResultHandler.Outcome.IGNORED_UNKNOWN_REQUEST;
import static com.couponrush.llm.LlmResultHandler.Outcome.IGNORED_UNSUPPORTED_VERSION;
import static org.assertj.core.api.Assertions.assertThat;

/** 결과 반영 규칙 (PENDING 일 때만) — Kafka 를 거치지 않고 핸들러·저장소를 직접 불러 결정적으로 검증한다 */
@IntegrationTest
class LlmResultHandlerTest {

    @Autowired LlmResultHandler handler;
    @Autowired LlmRequestRepository repository;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM llm_requests");
    }

    private UUID pending(Instant deadline) {
        UUID id = UUID.randomUUID();
        Instant created = deadline.minus(Duration.ofSeconds(60));
        repository.save(LlmRequest.pending(id, LlmRequestType.EVENT_COPY, 1L, "{\"keywords\":\"봄\"}", created, deadline));
        return id;
    }

    private UUID pending() {
        return pending(Instant.now().plusSeconds(60));
    }

    private static LlmResultMessage completed(UUID id, String name) {
        return LlmResultMessage.completed(id, List.of(new EventCopyCandidate(name, "설명")), "fake", 10, 5, false, 1, Instant.now());
    }

    @Test
    void 같은_결과가_두_번_오면_첫_번째만_반영한다() {
        UUID id = pending();

        assertThat(handler.handle(completed(id, "첫 결과"))).isEqualTo(APPLIED);
        assertThat(handler.handle(completed(id, "재전달된 다른 결과"))).isEqualTo(IGNORED_ALREADY_FINISHED);

        LlmRequest saved = repository.findById(id).orElseThrow();
        assertThat(saved.getResult()).contains("첫 결과").doesNotContain("재전달된");
    }

    @Test
    void 실패가_먼저_반영되면_뒤에_온_성공은_무시한다() {
        UUID id = pending();

        handler.handle(LlmResultMessage.failed(id, LlmFailureReason.INVALID_OUTPUT, "fake", 30, 40, 1, Instant.now()));
        assertThat(handler.handle(completed(id, "늦은 성공"))).isEqualTo(IGNORED_ALREADY_FINISHED);

        LlmRequest saved = repository.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(LlmRequestStatus.FAILED);
        assertThat(saved.getFailureReason()).isEqualTo(LlmFailureReason.INVALID_OUTPUT);
        assertThat(saved.getInputTokens() + saved.getOutputTokens()).as("실패해도 쓴 토큰은 남는다").isEqualTo(70);
        assertThat(saved.getResult()).isNull();
    }

    @Test
    void 모르는_요청의_결과는_예외_없이_무시한다() {
        assertThat(handler.handle(completed(UUID.randomUUID(), "누구?"))).isEqualTo(IGNORED_UNKNOWN_REQUEST);
    }

    @Test
    void 지원하지_않는_스키마_버전은_반영하지_않는다() {
        UUID id = pending();
        LlmResultMessage future = new LlmResultMessage(99, id, LlmResultStatus.COMPLETED,
                List.of(new EventCopyCandidate("미래", "버전")), null, "fake", 0, 0, false, 0, Instant.now());

        assertThat(handler.handle(future)).isEqualTo(IGNORED_UNSUPPORTED_VERSION);
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(LlmRequestStatus.PENDING);
    }

    @Test
    void 결과와_타임아웃이_동시에_와도_한쪽만_반영되고_행이_섞이지_않는다() throws Exception {
        int rows = 30;
        List<UUID> ids = new ArrayList<>();
        Instant past = Instant.now().minusSeconds(1);   // 이미 마감 → 타임아웃도 성립하는 상태
        for (int i = 0; i < rows; i++) {
            ids.add(pending(past));
        }

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (UUID id : ids) {
                CountDownLatch start = new CountDownLatch(1);
                futures.add(executor.submit(() -> {
                    start.await();
                    return handler.handle(completed(id, "경합")) == APPLIED ? 1 : 0;
                }));
                futures.add(executor.submit(() -> {
                    start.await();
                    return repository.timeoutIfExpired(id, Instant.now());
                }));
                start.countDown();
            }
            int applied = 0;
            for (Future<Integer> f : futures) {
                applied += f.get();
            }
            assertThat(applied).as("행마다 정확히 한 번씩만 바뀐다").isEqualTo(rows);
        } finally {
            executor.shutdownNow();
        }

        for (UUID id : ids) {
            LlmRequest saved = repository.findById(id).orElseThrow();
            if (saved.getStatus() == LlmRequestStatus.COMPLETED) {
                assertThat(saved.getResult()).isNotNull();
                assertThat(saved.getFailureReason()).isNull();
            } else {
                assertThat(saved.getStatus()).isEqualTo(LlmRequestStatus.FAILED);
                assertThat(saved.getFailureReason()).isEqualTo(LlmFailureReason.TIMEOUT);
                assertThat(saved.getResult()).isNull();
            }
        }
    }
}
