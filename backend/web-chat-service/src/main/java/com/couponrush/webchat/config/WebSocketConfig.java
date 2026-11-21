package com.couponrush.webchat.config;

import com.couponrush.webchat.auth.StompInboundGuard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket 설정.
 * <ul>
 *   <li>SockJS 를 쓰지 않는다 — 지원 브라우저는 모두 WebSocket 을 쓸 수 있고, 대체 전송(긴 폴링)은 서버 부담·코드만 늘린다</li>
 *   <li>단순 브로커(서버 메모리): D1 은 1대 기준. 여러 대는 D3 에서 Redis pub/sub 으로 직접 잇는다
 *       (RabbitMQ 릴레이를 쓰면 scale-out 문제가 숨겨져 학습 목적에 맞지 않음)</li>
 *   <li>preserveReceiveOrder / preservePublishOrder: 기본 입·출력 채널은 스레드 풀이라 같은 연결의 프레임 순서가 바뀔 수 있다
 *       → 한 사람이 보낸 메시지·한 사람에게 가는 메시지의 순서를 지킨다 (연결 단위 직렬화, 연결끼리는 병렬)</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebChatProperties properties;
    private final StompInboundGuard inboundGuard;
    private final TaskScheduler brokerScheduler;

    /**
     * 하트비트용 스케줄러는 브로커 설정이 만드는 messageBrokerTaskScheduler 를 쓴다
     * (이 설정이 그 Bean 을 만드는 쪽이라 @Lazy 로 순환을 끊는다 — Spring 문서의 방식).
     */
    public WebSocketConfig(WebChatProperties properties, StompInboundGuard inboundGuard,
                           @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler brokerScheduler) {
        this.properties = properties;
        this.inboundGuard = inboundGuard;
        this.brokerScheduler = brokerScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(properties.endpoint())
                .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long heartbeatMs = properties.heartbeat().toMillis();
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{heartbeatMs, heartbeatMs})
                .setTaskScheduler(brokerScheduler);
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(inboundGuard);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        WebChatProperties.Transport transport = properties.transport();
        registration.setMessageSizeLimit(Math.toIntExact(transport.messageSizeLimit().toBytes()))
                .setSendBufferSizeLimit(Math.toIntExact(transport.sendBufferSizeLimit().toBytes()))
                .setSendTimeLimit(Math.toIntExact(transport.sendTimeLimit().toMillis()));
    }
}
