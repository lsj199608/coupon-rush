package com.couponrush.webchat;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 통합 테스트 공통 설정 — 설정이 같아야 Spring 컨텍스트(와 컨테이너)를 테스트 클래스끼리 다시 쓴다.
 * 도배 한도는 작게(3개/1분), 저장 간격·재시도 간격은 짧게.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.web-chat.rate-limit.max-messages=3",
        "app.web-chat.rate-limit.window=1m",
        "app.web-chat.history.flush-interval=50ms",
        "app.web-chat.history.retry-delay=200ms",
        "app.web-chat.history.page-size=3"
})
@Import(TestcontainersConfiguration.class)
public @interface WebChatIntegrationTest {
}
