package com.couponrush.webchat;

import com.couponrush.webchat.contract.WebChatTicket;
import com.couponrush.webchat.contract.WebChatTickets;
import com.couponrush.webchat.room.WebChatDestinations;
import com.couponrush.webchat.room.WebChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 실제 STOMP 클라이언트(WebSocketStompClient)로 서버에 붙어 방 구독·보내기·거절을 검증한다.
 * <p>
 * 회원 연결은 실제 티켓으로 한다 — coupon-api 처럼 Redis(Testcontainers)에 티켓을 넣고 CONNECT 의 ticket 헤더로 보낸다 (D2).
 * 티켓 없이 연결하면 비로그인(읽기만).
 * 도배 제한 상태가 컨텍스트에 남으므로 테스트마다 새 회원 ID 를 쓴다.
 * <p>
 * 구독은 비동기로 등록되므로, 보내기 전에 서버에서 그 주소로 표시용 메시지(probe)를 보내 받을 때까지 기다린다.
 * "받지 않음" 검증: 입구 검사·컨트롤러 거절은 브로커에 가기 전에 끝나므로, 거절 응답을 받은 시점에 방 메시지가 없으면 끝까지 없다.
 */
@WebChatIntegrationTest
class WebChatRoomTest {

    private static final AtomicLong USER_IDS = new AtomicLong(1000);
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort
    int port;

    @Autowired
    SimpMessagingTemplate messagingTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    ObjectMapper objectMapper;

    private WebSocketStompClient stompClient;
    private final List<Client> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new MappingJackson2MessageConverter())));
    }

    @AfterEach
    void tearDown() {
        clients.forEach(Client::close);
        stompClient.stop();
        redisTemplate.delete(redisTemplate.keys("web-chat:*"));
    }

    @Test
    void 로그인_회원의_메시지가_같은_방_모두에게_가고_보낸_사람은_서버가_정한다() throws Exception {
        long eventId = 11;
        long userId = newUserId();
        Client sender = connect(userId);
        Client anonymous = connect(null);
        Room senderRoom = sender.subscribeRoom(eventId);
        Room anonymousRoom = anonymous.subscribeRoom(eventId);

        // 바디에 보낸 사람·ID 를 넣어도 서버 값으로 바뀐다
        sender.send(sendTo(eventId), Map.of("content", "  안녕하세요\n반가워요  ",
                "senderId", 999, "senderNickname", "운영자", "messageId", "fake", "eventId", 77));

        for (Room room : List.of(senderRoom, anonymousRoom)) {
            JsonNode message = room.next();
            assertThat(message.get("eventId").asLong()).isEqualTo(eventId);
            assertThat(message.get("senderId").asLong()).isEqualTo(userId);
            assertThat(message.get("senderNickname").asText()).isEqualTo(nickname(userId));
            assertThat(message.get("content").asText()).isEqualTo("안녕하세요\n반가워요");
            assertThat(message.get("messageId").asText()).hasSize(36).isNotEqualTo("fake");
            assertThat(message.get("sentAt").asText()).isNotBlank();
        }
    }

    @Test
    void 다른_방에는_가지_않는다() throws Exception {
        long userId = newUserId();
        Client sender = connect(userId);
        Client listener = connect(null);
        Room room1 = listener.subscribeRoom(21);
        Room room2 = listener.subscribeRoom(22);

        sender.send(sendTo(21), Map.of("content", "1번 방"));
        assertThat(room1.next().get("content").asText()).isEqualTo("1번 방");

        sender.send(sendTo(22), Map.of("content", "2번 방"));
        assertThat(room2.next().get("content").asText()).isEqualTo("2번 방"); // 2번 방의 첫 메시지 = 1번 방 메시지가 새지 않음
        assertThat(room1.poll()).isNull();
    }

    @Test
    void 비로그인_연결은_읽기만_되고_보내면_연결이_끊긴다() throws Exception {
        long eventId = 31;
        Client listener = connect(null);
        Room room = listener.subscribeRoom(eventId);
        Client anonymous = connect(null);

        anonymous.send(sendTo(eventId), Map.of("content", "안녕"));

        assertThat(anonymous.nextErrorFrame()).isEqualTo("WEB_CHAT_LOGIN_REQUIRED");
        await().atMost(5, TimeUnit.SECONDS).until(() -> !anonymous.session.isConnected());
        assertThat(room.poll()).isNull();
    }

    @Test
    void 브로커_주소에_직접_보내면_거절한다() throws Exception {
        long eventId = 41;
        Client listener = connect(null);
        Room room = listener.subscribeRoom(eventId);
        Client attacker = connect(newUserId());

        // /topic 에 직접 보내면 컨트롤러(보낸 사람 채우기·검사)를 건너뛰게 되므로 입구에서 막는다
        attacker.send("/topic/events/" + eventId, Map.of("content", "가짜", "senderId", 1, "senderNickname", "운영자"));

        assertThat(attacker.nextErrorFrame()).isEqualTo("WEB_CHAT_FORBIDDEN_DESTINATION");
        assertThat(room.poll()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/other", "/topic/events/0", "/topic/events/abc", "/queue/errors", "/user/1/queue/errors"})
    void 허용하지_않은_주소는_구독할_수_없다(String destination) throws Exception {
        Client client = connect(newUserId());

        client.session.subscribe(destination, new JsonFrames(new LinkedBlockingQueue<>(), new LinkedBlockingQueue<>()));

        assertThat(client.nextErrorFrame()).isEqualTo("WEB_CHAT_FORBIDDEN_DESTINATION");
    }

    @Test
    void 너무_긴_메시지와_빈_메시지는_보낸_사람에게만_알리고_연결은_유지한다() throws Exception {
        long eventId = 51;
        long userId = newUserId();
        Client sender = connect(userId);
        Room room = sender.subscribeRoom(eventId);
        Errors errors = sender.subscribeErrors();

        sender.send(sendTo(eventId), Map.of("content", "가".repeat(WebChatMessage.CONTENT_MAX + 1)));
        assertThat(errors.next().get("code").asText()).isEqualTo("WEB_CHAT_MESSAGE_TOO_LONG");

        sender.send(sendTo(eventId), Map.of("content", "   "));
        assertThat(errors.next().get("code").asText()).isEqualTo("WEB_CHAT_EMPTY_MESSAGE");
        assertThat(room.poll()).isNull();

        String max = "가".repeat(WebChatMessage.CONTENT_MAX);
        sender.send(sendTo(eventId), Map.of("content", max));
        assertThat(room.next().get("content").asText()).isEqualTo(max);
        assertThat(sender.session.isConnected()).isTrue();
    }

    @Test
    void JSON이_아닌_바디는_형식_오류로_알린다() throws Exception {
        long eventId = 61;
        long userId = newUserId();
        Client sender = connect(userId);
        Room room = sender.subscribeRoom(eventId);
        Errors errors = sender.subscribeErrors();

        sender.send(sendTo(eventId), "그냥 글자"); // text/plain

        JsonNode error = errors.next();
        assertThat(error.get("code").asText()).isEqualTo("WEB_CHAT_INVALID_MESSAGE");
        assertThat(error.get("message").asText()).isEqualTo("메시지 형식이 올바르지 않습니다.");
        assertThat(room.poll()).isNull();
    }

    @Test
    void 도배_제한은_회원_기준이라_탭을_여러_개_열어도_합산된다() throws Exception {
        long eventId = 71;
        long userId = newUserId();
        Client tab1 = connect(userId);
        Client tab2 = connect(userId);
        Client other = connect(newUserId());
        Room room = tab1.subscribeRoom(eventId);
        Errors tab1Errors = tab1.subscribeErrors();
        Errors tab2Errors = tab2.subscribeErrors();

        tab1.send(sendTo(eventId), Map.of("content", "1"));
        tab2.send(sendTo(eventId), Map.of("content", "2"));
        tab1.send(sendTo(eventId), Map.of("content", "3"));
        List<String> received = List.of(room.next(), room.next(), room.next()).stream()
                .map(m -> m.get("content").asText()).toList();
        assertThat(received).containsExactlyInAnyOrder("1", "2", "3");

        tab2.send(sendTo(eventId), Map.of("content", "4")); // 한도 3 (테스트 설정)
        assertThat(tab2Errors.next().get("code").asText()).isEqualTo("WEB_CHAT_RATE_LIMITED");
        assertThat(room.poll()).isNull();

        other.send(sendTo(eventId), Map.of("content", "다른 회원")); // 다른 회원은 영향 없음
        assertThat(room.next().get("content").asText()).isEqualTo("다른 회원");

        // 거절 사유는 보낸 탭에만 (broadcast=false) — 부정 검증이라 짧게 기다린다
        assertThat(tab1Errors.queue.poll(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(tab2.session.isConnected()).isTrue();
    }

    @Test
    void 허용하지_않은_Origin의_브라우저는_연결할_수_없다() throws Exception {
        assertThatThrownBy(() -> connect(null, "http://evil.example"))
                .isInstanceOf(ExecutionException.class);

        Client allowed = connect(null, ALLOWED_ORIGIN);
        assertThat(allowed.session.isConnected()).isTrue();
    }

    @Test
    void 프레임_크기_상한을_넘으면_연결을_끊는다() throws Exception {
        Client client = connect(newUserId());

        client.send(sendTo(81), Map.of("content", "a".repeat(10_000))); // 상한 8KB

        await().atMost(5, TimeUnit.SECONDS).until(() -> !client.session.isConnected());
    }

    // ── 연결 티켓 (D2) ──

    @Test
    void 티켓의_회원으로_연결되고_티켓은_한_번_쓰면_사라진다() throws Exception {
        long eventId = 91;
        long userId = newUserId();
        String ticket = issueTicket(userId);

        Client sender = connectWithTicket(ticket, null);
        assertThat(redisTemplate.hasKey(WebChatTickets.redisKey(ticket))).as("연결하면서 지운다(GETDEL)").isFalse();

        Room room = sender.subscribeRoom(eventId);
        sender.send(sendTo(eventId), Map.of("content", "티켓으로 왔어요"));
        JsonNode message = room.next();
        assertThat(message.get("senderId").asLong()).isEqualTo(userId);
        assertThat(message.get("senderNickname").asText()).as("닉네임은 티켓에 담긴 값").isEqualTo(nickname(userId));

        assertThat(connectExpectingRejection(ticket)).as("같은 티켓 재사용").isEqualTo("WEB_CHAT_UNAUTHORIZED");
    }

    @Test
    void 없거나_만료된_티켓과_형식이_아닌_티켓은_연결을_거절한다() throws Exception {
        byte[] bytes = new byte[WebChatTickets.RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        String neverIssued = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); // 만료(TTL 지나 사라짐)와 같은 상태

        assertThat(connectExpectingRejection(neverIssued)).isEqualTo("WEB_CHAT_UNAUTHORIZED");
        assertThat(connectExpectingRejection("not-a-ticket")).isEqualTo("WEB_CHAT_UNAUTHORIZED");
        assertThat(connectExpectingRejection("a".repeat(5000))).as("긴 헤더 — Redis 를 보기 전에 거절").isEqualTo("WEB_CHAT_UNAUTHORIZED");
    }

    @Test
    void 같은_티켓으로_동시에_연결해도_하나만_성공한다() throws Exception {
        String ticket = issueTicket(newUserId());
        int attempts = 8;
        java.util.concurrent.atomic.AtomicInteger connected = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.CompletableFuture<StompSession>> futures = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            StompHeaders headers = new StompHeaders();
            headers.add(WebChatTickets.CONNECT_HEADER, ticket);
            futures.add(stompClient.connectAsync("ws://localhost:" + port + "/ws/web-chat", new WebSocketHttpHeaders(), headers,
                    new StompSessionHandlerAdapter() {
                        @Override
                        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                            connected.incrementAndGet();
                        }

                        @Override
                        public void handleFrame(StompHeaders frameHeaders, Object payload) {
                            rejected.incrementAndGet();
                        }
                    }));
        }

        await().atMost(10, TimeUnit.SECONDS).until(() -> connected.get() + rejected.get() == attempts);
        assertThat(connected).as("GETDEL 은 원자적 — 꺼낸 쪽 하나만").hasValue(1);
        futures.stream().filter(f -> f.isDone() && !f.isCompletedExceptionally())
                .forEach(f -> clients.add(new Client(f.join(), new LinkedBlockingQueue<>())));
    }

    @Test
    void 티켓_내용을_읽을_수_없으면_연결을_거절한다() throws Exception {
        byte[] bytes = new byte[WebChatTickets.RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(WebChatTickets.redisKey(ticket), "{\"userId\":0}", WebChatTickets.TTL); // 계약 위반

        assertThat(connectExpectingRejection(ticket)).isEqualTo("WEB_CHAT_UNAUTHORIZED");
    }

    // ── 도우미 ──

    private static long newUserId() {
        return USER_IDS.incrementAndGet();
    }

    private static String nickname(long userId) {
        return "회원" + userId;
    }

    private static String sendTo(long eventId) {
        return "/app/events/" + eventId + "/chat";
    }

    private Client connect(@Nullable Long userId) throws Exception {
        return connect(userId, null);
    }

    /** 회원이면 coupon-api 처럼 티켓을 만들어 그 티켓으로 연결한다 (null = 티켓 없이 = 비로그인). */
    private Client connect(@Nullable Long userId, @Nullable String origin) throws Exception {
        return connectWithTicket(userId == null ? null : issueTicket(userId), origin);
    }

    /**
     * coupon-api WebChatTicketService 와 같은 방식으로 티켓을 만든다 — 같은 계약(WebChatTickets·WebChatTicket)을 쓰므로
     * 키·값 모양이 실제 발급과 같다. 티켓 원문을 돌려준다.
     */
    private String issueTicket(long userId) throws Exception {
        byte[] bytes = new byte[WebChatTickets.RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(WebChatTickets.redisKey(ticket),
                objectMapper.writeValueAsString(new WebChatTicket(userId, nickname(userId), Instant.now())), WebChatTickets.TTL);
        return ticket;
    }

    private Client connectWithTicket(@Nullable String ticket, @Nullable String origin) throws Exception {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (origin != null) {
            handshake.setOrigin(origin);
        }
        StompHeaders connectHeaders = new StompHeaders();
        if (ticket != null) {
            connectHeaders.add(WebChatTickets.CONNECT_HEADER, ticket);
        }
        BlockingQueue<String> errorFrames = new LinkedBlockingQueue<>();
        StompSession session = stompClient.connectAsync("ws://localhost:" + port + "/ws/web-chat", handshake, connectHeaders,
                errorRecorder(errorFrames)).get(5, TimeUnit.SECONDS);
        Client client = new Client(session, errorFrames);
        clients.add(client);
        return client;
    }

    /**
     * 연결이 거절될 티켓으로 CONNECT 하고 ERROR 프레임의 사유를 돌려준다.
     * 거절되면 CONNECTED 가 오지 않아 connectAsync 가 끝나지 않으므로 기다리지 않는다 (ERROR 프레임만 본다).
     */
    private String connectExpectingRejection(String ticket) throws InterruptedException {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(WebChatTickets.CONNECT_HEADER, ticket);
        BlockingQueue<String> errorFrames = new LinkedBlockingQueue<>();
        stompClient.connectAsync("ws://localhost:" + port + "/ws/web-chat", new WebSocketHttpHeaders(), connectHeaders,
                errorRecorder(errorFrames));
        String code = errorFrames.poll(5, TimeUnit.SECONDS);
        assertThat(code).as("연결 거절 ERROR 프레임").isNotNull();
        return code;
    }

    private static StompSessionHandlerAdapter errorRecorder(BlockingQueue<String> errorFrames) {
        return new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errorFrames.add(String.valueOf(headers.getFirst("message"))); // 세션 핸들러로는 ERROR 프레임만 온다
            }
        };
    }

    private final class Client {
        final StompSession session;
        final BlockingQueue<String> errorFrames;

        Client(StompSession session, BlockingQueue<String> errorFrames) {
            this.session = session;
            this.errorFrames = errorFrames;
        }

        void send(String destination, Object body) {
            session.send(destination, body);
        }

        Room subscribeRoom(long eventId) {
            String destination = "/topic/events/" + eventId;
            BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
            BlockingQueue<JsonNode> probes = new LinkedBlockingQueue<>();
            session.subscribe(destination, new JsonFrames(queue, probes));
            awaitProbe(probes, () -> messagingTemplate.convertAndSend(destination, Map.of("probe", true)));
            return new Room(queue);
        }

        /**
         * 내 오류 주소 구독. 확인은 같은 연결로 형식이 틀린 메시지 하나를 보내 그 거절을 받는 것으로 한다 —
         * 같은 연결의 프레임은 순서대로 처리되므로(preserveReceiveOrder) 거절이 오면 구독은 이미 등록돼 있다.
         * 형식 오류는 컨트롤러 메서드 전(인자 변환)에서 나서 도배 한도에 세지 않는다.
         * (서버에서 convertAndSendToUser 로 확인하지 않는 이유: preservePublishOrder 에서 세션이 여러 개인 회원에게 보내면
         * Spring 이 "Expected mutable SimpMessageHeaderAccessor" 로 실패한다 — 앱은 broadcast=false 라 해당 없음)
         */
        Errors subscribeErrors() throws InterruptedException {
            BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
            session.subscribe(WebChatDestinations.USER_ERRORS, new JsonFrames(queue, new LinkedBlockingQueue<>()));
            session.send(sendTo(1), "구독 확인"); // text/plain → WEB_CHAT_INVALID_MESSAGE
            Errors errors = new Errors(queue);
            assertThat(errors.next().get("code").asText()).isEqualTo("WEB_CHAT_INVALID_MESSAGE");
            return errors;
        }

        String nextErrorFrame() throws InterruptedException {
            String code = errorFrames.poll(5, TimeUnit.SECONDS);
            assertThat(code).as("ERROR 프레임").isNotNull();
            return code;
        }

        void close() {
            try {
                if (session.isConnected()) {
                    session.disconnect();
                }
            } catch (RuntimeException e) {
                // 서버가 막 끊은 연결 (ERROR 프레임 테스트) — 정리 중이라 무시
            }
        }
    }

    /** 표시용 메시지가 올 때까지 다시 보내며 기다린다 (= 구독이 서버에 등록됨). */
    private static void awaitProbe(BlockingQueue<JsonNode> probes, Runnable sendProbe) {
        await().atMost(5, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).until(() -> {
            sendProbe.run();
            return !probes.isEmpty();
        });
    }

    private record Room(BlockingQueue<JsonNode> queue) {
        JsonNode next() throws InterruptedException {
            JsonNode message = queue.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("방 메시지").isNotNull();
            return message;
        }

        JsonNode poll() {
            return queue.poll();
        }
    }

    private record Errors(BlockingQueue<JsonNode> queue) {
        JsonNode next() throws InterruptedException {
            JsonNode error = queue.poll(5, TimeUnit.SECONDS);
            assertThat(error).as("거절 사유").isNotNull();
            return error;
        }
    }

    /** JSON 프레임을 받아 표시용(probe)과 실제 메시지를 나눠 담는다. */
    private final class JsonFrames implements StompFrameHandler {
        private final BlockingQueue<JsonNode> messages;
        private final BlockingQueue<JsonNode> probes;

        JsonFrames(BlockingQueue<JsonNode> messages, BlockingQueue<JsonNode> probes) {
            this.messages = messages;
            this.probes = probes;
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return JsonNode.class; // 서버가 application/json 으로 보낸다 → Jackson 변환기
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            JsonNode node = (JsonNode) payload;
            (node.has("probe") ? probes : messages).add(node);
        }
    }
}
