package com.couponrush.global.config;

import com.couponrush.TestcontainersConfiguration;
import com.couponrush.coupon.reconcile.CouponStockReconcileScheduler;
import com.couponrush.coupon.reconcile.CouponStockReconciler;
import com.couponrush.llm.LlmRequestTimeoutScheduler;
import com.couponrush.llm.LlmRequestTimeouts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ShedLock (8-5): 서버가 여러 대여도 @Scheduled 작업은 한 대에서만.
 * <p>
 * 실제 스케줄러 Bean(ShedLock 프록시)의 메서드를 직접 불러 "서버 두 대가 같은 때 깨어난 상황"을 만든다 —
 * 잠금 판정은 공유 DB(shedlock 표)로만 하므로, 같은 앱 안의 두 스레드와 다른 Pod 두 개는 판정이 같다.
 * <p>
 * @IntegrationTest 는 스케줄러를 끄므로 여기서는 켜고(자동 실행은 1시간 뒤로 미룸) 따로 컨텍스트를 띄운다.
 * 시간 경과는 기다리지 않고 shedlock 줄의 lock_until 을 과거로 바꿔 흉내 낸다(usingDbTime — 판정 시계가 DB 라서 앱 Clock 으로는 못 움직인다).
 */
@SpringBootTest(properties = {
        "KAKAO_CLIENT_ID=test-kakao-client-id",
        "KAKAO_CLIENT_SECRET=test-kakao-client-secret",
        "NAVER_CLIENT_ID=test-naver-client-id",
        "NAVER_CLIENT_SECRET=test-naver-client-secret",
        "GOOGLE_CLIENT_ID=test-google-client-id",
        "GOOGLE_CLIENT_SECRET=test-google-client-secret",
        "JWT_SECRET=test-jwt-secret-key-must-be-at-least-32-bytes",
        "FRONTEND_OAUTH2_REDIRECT_URI=http://localhost:5173/oauth/callback",
        "INTERNAL_API_TOKEN=test-internal-token",
        "app.coupon.reconcile.enabled=true",
        "app.coupon.reconcile.initial-delay=PT1H",
        "app.llm.timeout-sweep.enabled=true",
        "app.llm.timeout-sweep.initial-delay=PT1H"
})
@Import(TestcontainersConfiguration.class)
class SchedulerLockTest {

    private static final String SWEEP_LOCK = "llm-request-timeout-sweep";
    private static final String RECONCILE_LOCK = "coupon-stock-reconcile";

    @Autowired LlmRequestTimeoutScheduler sweepScheduler;
    @Autowired CouponStockReconcileScheduler reconcileScheduler;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoSpyBean LlmRequestTimeouts timeouts;
    @MockitoSpyBean CouponStockReconciler reconciler;

    /**
     * 잠금 줄은 지우지 않고 풀기만 한다. ShedLock 은 "이 이름의 줄은 이미 만들었다"를 메모리에 기억하고 그다음부터 UPDATE 만 하므로,
     * 줄을 DELETE 하면 UPDATE 가 0행 → "다른 서버가 잡고 있음"으로 보고 계속 건너뛴다 (운영에서도 shedlock 줄을 손으로 지우지 말 것).
     */
    @BeforeEach
    void setUp() {
        expireAllLocks();
        doReturn(0).when(timeouts).sweep();
        doReturn(List.of()).when(reconciler).reconcileRecentlyEnded();
    }

    @AfterEach
    void tearDown() {
        expireAllLocks();
    }

    @Test
    void 한_서버가_실행_중이면_다른_서버는_기다리지_않고_건너뛴다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return 0;
        }).when(timeouts).sweep();

        // 서버 A: 잠금을 잡고 작업 안에서 멈춰 있음
        CompletableFuture<Void> serverA = CompletableFuture.runAsync(sweepScheduler::sweep);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        // 서버 B: 같은 작업을 부르면 잠금이 잡혀 있어 실행하지 않고 바로 돌아온다 (기다리지 않음)
        CompletableFuture<Void> serverB = CompletableFuture.runAsync(sweepScheduler::sweep);
        serverB.get(5, TimeUnit.SECONDS);

        release.countDown();
        serverA.get(10, TimeUnit.SECONDS);

        verify(timeouts, times(1)).sweep();
    }

    @Test
    void 작업이_금방_끝나도_lockAtLeastFor_동안은_다시_돌지_않는다() {
        sweepScheduler.sweep();
        sweepScheduler.sweep();   // 늦게 깨어난 다른 서버

        verify(timeouts, times(1)).sweep();
        // 끝난 뒤에도 표시가 남아 있다: lock_until = 시작 + 25초 (주기 30초보다 조금 짧게)
        Double heldSeconds = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (lock_until - locked_at)) FROM shedlock WHERE name = ?",
                Double.class, SWEEP_LOCK);
        assertThat(heldSeconds).isBetween(24.0, 26.0);
    }

    @Test
    void 잠금_시간이_지나면_다음_주기에는_다시_실행된다() {
        sweepScheduler.sweep();
        expireLock(SWEEP_LOCK);

        sweepScheduler.sweep();

        verify(timeouts, times(2)).sweep();
    }

    @Test
    void 잠금은_작업마다_따로라_다른_작업은_막지_않는다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return 0;
        }).when(timeouts).sweep();

        CompletableFuture<Void> sweeping = CompletableFuture.runAsync(sweepScheduler::sweep);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        // 타임아웃 정리가 잠금을 잡고 실행 중이어도 대조 점검은 그대로 돈다
        reconcileScheduler.reconcileEndedEvents();
        verify(reconciler, times(1)).reconcileRecentlyEnded();

        release.countDown();
        sweeping.get(10, TimeUnit.SECONDS);
    }

    @Test
    void 대조_점검은_9분_동안_다른_서버가_다시_돌리지_않는다() {
        reconcileScheduler.reconcileEndedEvents();
        reconcileScheduler.reconcileEndedEvents();

        verify(reconciler, times(1)).reconcileRecentlyEnded();
        Double heldSeconds = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (lock_until - locked_at)) FROM shedlock WHERE name = ?",
                Double.class, RECONCILE_LOCK);
        assertThat(heldSeconds).isBetween(539.0, 541.0);
    }

    @Test
    void 잠금_줄에_실행한_서버가_남는다() {
        sweepScheduler.sweep();

        String lockedBy = jdbcTemplate.queryForObject(
                "SELECT locked_by FROM shedlock WHERE name = ?", String.class, SWEEP_LOCK);
        // 기본값 = 호스트 이름 (Kubernetes 에서는 Pod 이름) — 어느 Pod 가 돌렸는지 DB 에서 바로 보인다
        assertThat(lockedBy).isNotBlank();
        verify(reconciler, never()).reconcileRecentlyEnded();
    }

    /** 시간이 흐른 것처럼: lock_until 을 과거로 */
    private void expireLock(String name) {
        jdbcTemplate.update("UPDATE shedlock SET lock_until = lock_until - interval '1 hour' WHERE name = ?", name);
    }

    private void expireAllLocks() {
        jdbcTemplate.update("UPDATE shedlock SET lock_until = lock_until - interval '1 hour'");
    }
}
