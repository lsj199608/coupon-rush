package com.couponrush;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 통합 테스트 공통 설정.
 * - 모든 통합 테스트가 같은 설정을 쓰므로 Spring 컨텍스트/컨테이너가 한 번만 뜬다 (컨텍스트 캐시)
 * - 비밀값은 테스트용 더미로 고정 → infra/.env 가 없거나 실제 키가 들어 있어도 테스트 결과가 같다
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "KAKAO_CLIENT_ID=test-kakao-client-id",
        "KAKAO_CLIENT_SECRET=test-kakao-client-secret",
        "NAVER_CLIENT_ID=test-naver-client-id",
        "NAVER_CLIENT_SECRET=test-naver-client-secret",
        "GOOGLE_CLIENT_ID=test-google-client-id",
        "GOOGLE_CLIENT_SECRET=test-google-client-secret",
        "JWT_SECRET=test-jwt-secret-key-must-be-at-least-32-bytes",
        "FRONTEND_OAUTH2_REDIRECT_URI=http://localhost:5173/oauth/callback",
        // 대조 점검 스케줄러는 끈다 (테스트는 CouponStockReconciler 를 직접 호출) — 백그라운드 실행이 테스트 데이터를 읽지 않게
        "app.coupon.reconcile.enabled=false",
        // LLM 타임아웃 정리 스케줄러도 끈다 (테스트는 LlmRequestTimeouts.sweep() 을 직접 호출)
        "app.llm.timeout-sweep.enabled=false"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
