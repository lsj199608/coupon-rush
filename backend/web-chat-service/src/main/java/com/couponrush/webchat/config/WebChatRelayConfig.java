package com.couponrush.webchat.config;

import com.couponrush.webchat.room.WebChatRelay;
import com.couponrush.webchat.room.WebChatRelayListener;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 서버 간 전달(D3) — Redis 채널 구독.
 * <p>
 * <b>받은 메시지를 단일 스레드로 넘긴다.</b> 컨테이너 기본 실행기(SimpleAsyncTaskExecutor)는 메시지마다 새 스레드를 만들어
 * 같은 방의 메시지 순서가 뒤바뀔 수 있다 (spring-data-redis 3.5 dispatchMessage 확인). 리스너는 브로커에 넘기기만 해서
 * 스레드 하나로 충분하다 — 오래 걸리는 일(저장 등)을 여기에 넣지 않는다.
 * <p>
 * 실행기를 Bean 으로 등록하지 않는다: Executor Bean 이 생기면 Boot 의 기본 applicationTaskExecutor 가 물러나 이 스레드 하나가
 * 앱 기본 실행기 자리를 차지한다.
 * <p>
 * 구독 연결이 끊기면 컨테이너가 다시 구독한다 (끊긴 동안의 메시지는 잃는다 — {@link WebChatRelay}).
 */
@Configuration
public class WebChatRelayConfig implements DisposableBean {

    private final ThreadPoolTaskExecutor relayExecutor = singleThread();

    @Bean
    RedisMessageListenerContainer webChatRelayContainer(RedisConnectionFactory connectionFactory,
                                                        WebChatRelayListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(relayExecutor);
        container.addMessageListener(listener, new ChannelTopic(WebChatRelay.CHANNEL));
        return container;
    }

    /** 컨테이너(Bean)가 먼저 멈춘 뒤 불린다 — 이 설정 Bean 이 컨테이너보다 먼저 만들어지므로 나중에 없어진다. */
    @Override
    public void destroy() {
        relayExecutor.shutdown();
    }

    private static ThreadPoolTaskExecutor singleThread() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("web-chat-relay-");
        executor.initialize();
        return executor;
    }
}
