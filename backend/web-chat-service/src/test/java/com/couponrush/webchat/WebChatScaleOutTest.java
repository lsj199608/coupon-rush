package com.couponrush.webchat;

import com.couponrush.webchat.contract.WebChatTicket;
import com.couponrush.webchat.contract.WebChatTickets;
import com.couponrush.webchat.room.WebChatHistoryWriter;
import com.couponrush.webchat.room.WebChatRelay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import com.couponrush.webchat.room.WebChatDestinations;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 서버 2대 (D3) — A 는 이 테스트의 Spring 컨텍스트, B 는 같은 Redis·Postgres(Testcontainers)를 보는 두 번째 앱.
 * 브라우저가 로드 밸런서를 거쳐 서로 다른 서버에 붙은 상황이다.
 * <p>
 * B 의 설정은 명령줄 인자로 준다 — application.yml 이 infra/.env 를 읽으므로, 우선순위가 낮은 기본 속성으로 주면 로컬 값에 덮인다.
 * 테스트마다 새 이벤트(방)·회원 ID. B 로 보낸 테스트는 그 메시지가 저장될 때까지 기다리고 끝낸다 (다음 테스트의 B 저장 수 검사가 흔들리지 않게).
 */
@WebChatIntegrationTest
class WebChatScaleOutTest {

    private static final AtomicLong IDS = new AtomicLong(700_000);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static ConfigurableApplicationContext serverB;

    @LocalServerPort
    int portA;

    @Autowired
    SimpMessagingTemplate templateA;

    @Autowired
    WebChatHistoryWriter historyWriterA;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RedisConnectionDetails redis;

    @Autowired
    JdbcConnectionDetails jdbc;

    private WebSocketStompClient stompClient;
    private final List<StompSession> sessions = new ArrayList<>();
    private final List<Long> eventIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        if (serverB == null) {
            serverB = startServerB();
        }
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new MappingJackson2MessageConverter())));
    }

    @AfterEach
    void tearDown() {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        stompClient.stop();
        eventIds.forEach(id -> jdbcTemplate.update("DELETE FROM web_chat_messages WHERE event_id = ?", id));
    }

    @AfterAll
    static void stopServerB() {
        if (serverB != null) {
            serverB.close();
            serverB = null;
        }
    }

    @Test
    void 다른_서버에_붙은_사람끼리_양방향으로_대화한다() throws Exception {
        long eventId = newEventId();
        Server a = serverA();
        Server b = serverB();
        StompSession aliceOnA = connect(a, IDS.incrementAndGet());
        StompSession bobOnB = connect(b, IDS.incrementAndGet());
        BlockingQueue<JsonNode> aliceRoom = subscribe(a, aliceOnA, eventId);
        BlockingQueue<JsonNode> bobRoom = subscribe(b, bobOnB, eventId);

        aliceOnA.send(sendTo(eventId), Map.of("content", "A에서 보냄"));
        assertThat(next(bobRoom).get("content").asText()).isEqualTo("A에서 보냄");
        assertThat(next(aliceRoom).get("content").asText()).as("보낸 서버도 Redis 를 거쳐 받는다").isEqualTo("A에서 보냄");

        bobOnB.send(sendTo(eventId), Map.of("content", "B에서 보냄"));
        assertThat(next(aliceRoom).get("content").asText()).isEqualTo("B에서 보냄");
        assertThat(next(bobRoom).get("content").asText()).isEqualTo("B에서 보냄");

        awaitSavedCount(eventId, 2);
    }

    @Test
    void 저장은_메시지를_받은_서버만_한_번_한다() throws Exception {
        long eventId = newEventId();
        Server a = serverA();
        Server b = serverB();
        WebChatHistoryWriter historyWriterB = serverB.getBean(WebChatHistoryWriter.class);
        long savedByA = historyWriterA.savedCount();
        long savedByB = historyWriterB.savedCount();
        StompSession senderOnA = connect(a, IDS.incrementAndGet());
        StompSession listenerOnB = connect(b, null);
        BlockingQueue<JsonNode> room = subscribe(b, listenerOnB, eventId);

        senderOnA.send(sendTo(eventId), Map.of("content", "한 번만 저장"));
        String messageId = next(room).get("messageId").asText();

        awaitSavedCount(eventId, 1);
        Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM web_chat_messages WHERE message_id = ?::uuid",
                Integer.class, messageId);
        assertThat(rows).isEqualTo(1);
        assertThat(historyWriterA.savedCount() - savedByA).as("보낸 서버 A").isEqualTo(1);
        // B 는 방에 보내기만 한다 — B 가 저장하려 했다면 ON CONFLICT 로 행은 하나여도 B 의 저장 수가 늘었을 것이다
        assertThat(historyWriterB.savedCount() - savedByB).as("전달만 받은 서버 B").isZero();
    }

    @Test
    void 모든_서버의_구독자가_같은_순서로_받는다() throws Exception {
        long eventId = newEventId();
        Server a = serverA();
        Server b = serverB();
        StompSession senderOnA = connect(a, IDS.incrementAndGet());
        StompSession senderOnB = connect(b, IDS.incrementAndGet());
        BlockingQueue<JsonNode> roomOnA = subscribe(a, connect(a, null), eventId);
        BlockingQueue<JsonNode> roomOnB = subscribe(b, connect(b, null), eventId);

        for (int i = 1; i <= 3; i++) { // 도배 한도 3 (테스트 설정, 회원별)
            senderOnA.send(sendTo(eventId), Map.of("content", "A" + i));
            senderOnB.send(sendTo(eventId), Map.of("content", "B" + i));
        }

        List<String> seenOnA = contents(roomOnA, 6);
        List<String> seenOnB = contents(roomOnB, 6);
        assertThat(seenOnB).as("어느 서버에서 보든 대화 순서가 같다").isEqualTo(seenOnA);
        assertThat(seenOnA.stream().filter(c -> c.startsWith("A")).toList()).as("한 사람의 순서는 보낸 순서").containsExactly("A1", "A2", "A3");
        assertThat(seenOnA.stream().filter(c -> c.startsWith("B")).toList()).containsExactly("B1", "B2", "B3");

        awaitSavedCount(eventId, 6);
    }

    @Test
    void 읽을_수_없는_전달_메시지는_버리고_다음_메시지는_전달한다() throws Exception {
        long eventId = newEventId();
        Server b = serverB();
        BlockingQueue<JsonNode> room = subscribe(b, connect(b, null), eventId);
        StompSession sender = connect(serverA(), IDS.incrementAndGet());

        redisTemplate.convertAndSend(WebChatRelay.CHANNEL, "JSON 아님");
        redisTemplate.convertAndSend(WebChatRelay.CHANNEL, "{\"eventId\":" + eventId + "}"); // 필수 값 없음 (다른 버전이 보낸 모양)
        sender.send(sendTo(eventId), Map.of("content", "정상"));

        assertThat(next(room).get("content").asText()).isEqualTo("정상"); // 앞의 두 개가 방에 나왔다면 이게 첫 메시지가 아니다
        awaitSavedCount(eventId, 1);
    }

    @Test
    void 도배_제한은_서버가_달라도_회원_하나로_합산한다() throws Exception {
        long eventId = newEventId();
        long userId = IDS.incrementAndGet();
        Server a = serverA();
        Server b = serverB();
        StompSession tabOnA = connect(a, userId);
        StompSession tabOnB = connect(b, userId);
        BlockingQueue<JsonNode> room = subscribe(a, tabOnA, eventId);
        BlockingQueue<JsonNode> errorsOnB = subscribeErrors(tabOnB);

        tabOnA.send(sendTo(eventId), Map.of("content", "1"));
        tabOnB.send(sendTo(eventId), Map.of("content", "2"));
        tabOnA.send(sendTo(eventId), Map.of("content", "3"));
        assertThat(contents(room, 3)).containsExactlyInAnyOrder("1", "2", "3");

        tabOnB.send(sendTo(eventId), Map.of("content", "4")); // 한도 3 (테스트 설정) — B 는 자기 쪽 1개만 받았지만 장부는 하나
        JsonNode error = errorsOnB.poll(5, TimeUnit.SECONDS);
        assertThat(error).as("B 의 거절").isNotNull();
        assertThat(error.get("code").asText()).isEqualTo("WEB_CHAT_RATE_LIMITED");
        assertThat(room.poll()).isNull(); // 거절은 발행 전에 끝나므로 거절을 받은 시점에 방 메시지가 없으면 끝까지 없다

        awaitSavedCount(eventId, 3);
    }

    // ── 도우미 ──

    /**
     * 내 오류 주소 구독. 같은 연결로 JSON 이 아닌 바디를 보내 그 거절을 받으면 구독이 등록된 것이다 (같은 연결은 순서대로 처리).
     * 형식 오류는 도배 한도에 세지 않는다 (컨트롤러 인자 변환에서 남).
     */
    private BlockingQueue<JsonNode> subscribeErrors(StompSession session) throws InterruptedException {
        BlockingQueue<JsonNode> errors = new LinkedBlockingQueue<>();
        session.subscribe(WebChatDestinations.USER_ERRORS, jsonFrames(errors, new LinkedBlockingQueue<>()));
        session.send(sendTo(1), "구독 확인"); // text/plain → WEB_CHAT_INVALID_MESSAGE
        JsonNode first = errors.poll(5, TimeUnit.SECONDS);
        assertThat(first).as("오류 구독 확인").isNotNull();
        assertThat(first.get("code").asText()).isEqualTo("WEB_CHAT_INVALID_MESSAGE");
        return errors;
    }

    /** A 와 같은 컨테이너·같은 테스트 설정({@link WebChatIntegrationTest})으로 B 를 띄운다. */
    private ConfigurableApplicationContext startServerB() {
        RedisConnectionDetails.Standalone standalone = redis.getStandalone();
        return new SpringApplicationBuilder(WebChatServiceApplication.class).run(
                "--server.port=0",
                "--spring.data.redis.host=" + standalone.getHost(),
                "--spring.data.redis.port=" + standalone.getPort(),
                "--spring.datasource.url=" + jdbc.getJdbcUrl(),
                "--spring.datasource.username=" + jdbc.getUsername(),
                "--spring.datasource.password=" + jdbc.getPassword(),
                "--app.web-chat.rate-limit.max-messages=3",
                "--app.web-chat.rate-limit.window=1m",
                "--app.web-chat.history.flush-interval=50ms",
                "--app.web-chat.history.retry-delay=200ms",
                "--app.web-chat.history.page-size=3");
    }

    private record Server(int port, SimpMessagingTemplate template) {
    }

    private Server serverA() {
        return new Server(portA, templateA);
    }

    private Server serverB() {
        return new Server(((WebServerApplicationContext) serverB).getWebServer().getPort(),
                serverB.getBean(SimpMessagingTemplate.class));
    }

    private long newEventId() {
        long id = IDS.incrementAndGet();
        eventIds.add(id);
        return id;
    }

    private static String sendTo(long eventId) {
        return "/app/events/" + eventId + "/chat";
    }

    /** userId 가 있으면 coupon-api 처럼 티켓을 만들어 연결, null 이면 비로그인. */
    private StompSession connect(Server server, Long userId) throws Exception {
        StompHeaders headers = new StompHeaders();
        if (userId != null) {
            byte[] bytes = new byte[WebChatTickets.RANDOM_BYTES];
            RANDOM.nextBytes(bytes);
            String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            redisTemplate.opsForValue().set(WebChatTickets.redisKey(ticket),
                    objectMapper.writeValueAsString(new WebChatTicket(userId, "회원" + userId, Instant.now())), WebChatTickets.TTL);
            headers.add(WebChatTickets.CONNECT_HEADER, ticket);
        }
        StompSession session = stompClient.connectAsync("ws://localhost:" + server.port() + "/ws/web-chat",
                new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {
                }).get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    /**
     * 방을 구독하고, 그 서버의 브로커로 표시용 메시지(probe)를 보내 받을 때까지 기다린다 (= 구독이 그 서버에 등록됨).
     * probe 는 Redis 를 거치지 않으므로 다른 서버의 구독자에게는 가지 않는다.
     */
    private BlockingQueue<JsonNode> subscribe(Server server, StompSession session, long eventId) {
        String destination = "/topic/events/" + eventId;
        BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        BlockingQueue<JsonNode> probes = new LinkedBlockingQueue<>();
        session.subscribe(destination, jsonFrames(messages, probes));
        await().atMost(5, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).until(() -> {
            server.template().convertAndSend(destination, Map.of("probe", true));
            return !probes.isEmpty();
        });
        return messages;
    }

    /** JSON 프레임을 표시용(probe)과 실제 메시지로 나눠 담는다. */
    private static StompFrameHandler jsonFrames(BlockingQueue<JsonNode> messages, BlockingQueue<JsonNode> probes) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                JsonNode node = (JsonNode) payload;
                (node.has("probe") ? probes : messages).add(node);
            }
        };
    }

    private static JsonNode next(BlockingQueue<JsonNode> room) throws InterruptedException {
        JsonNode message = room.poll(5, TimeUnit.SECONDS);
        assertThat(message).as("방 메시지").isNotNull();
        return message;
    }

    private static List<String> contents(BlockingQueue<JsonNode> room, int count) throws InterruptedException {
        List<String> contents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            contents.add(next(room).get("content").asText());
        }
        return contents;
    }

    /** 지난 대화 API(A)에 count 개가 보일 때까지 — 저장은 비동기. */
    private void awaitSavedCount(long eventId, int count) {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + portA + "/api/web-chat/rooms/" + eventId + "/messages?size=10")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(objectMapper.readTree(response.body()).get("messages")).hasSize(count);
        });
    }
}
