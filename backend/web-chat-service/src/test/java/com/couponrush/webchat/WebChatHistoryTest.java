package com.couponrush.webchat;

import com.couponrush.webchat.auth.WebChatUser;
import com.couponrush.webchat.room.WebChatHistoryWriter;
import com.couponrush.webchat.room.WebChatMessage;
import com.couponrush.webchat.room.WebChatMessageRepository;
import com.couponrush.webchat.room.WebChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 대화 저장과 지난 대화 조회 (D6) — 실제 Postgres(web_chat 데이터베이스, 이 서비스의 Flyway 로 만든 테이블).
 * 보내기는 {@link WebChatService} 를 바로 부른다 (STOMP → 서비스 연결은 WebChatRoomTest 가 검증).
 * 테스트마다 새 이벤트 ID·회원 ID — 저장이 비동기라 앞 테스트의 메시지가 늦게 들어와도 섞이지 않게.
 * 설정: page-size 3, 저장 간격 50ms, 재시도 200ms ({@link WebChatIntegrationTest}).
 */
@WebChatIntegrationTest
class WebChatHistoryTest {

    private static final AtomicLong IDS = new AtomicLong(900_000);

    @LocalServerPort
    int port;

    @Autowired
    WebChatService webChatService;

    @Autowired
    WebChatHistoryWriter historyWriter;

    @Autowired
    WebChatMessageRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Long> eventIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("ALTER TABLE IF EXISTS web_chat_messages_broken RENAME TO web_chat_messages");
        eventIds.forEach(id -> jdbcTemplate.update("DELETE FROM web_chat_messages WHERE event_id = ?", id));
    }

    @Test
    void 보낸_메시지가_저장되고_실시간_메시지와_같은_값으로_조회된다() throws Exception {
        long eventId = newEventId();
        WebChatUser sender = new WebChatUser(IDS.incrementAndGet(), "닉네임😀");
        WebChatMessage sent = webChatService.send(eventId, sender, "  안녕하세요\n반가워요 😀  ");

        JsonNode page = awaitMessages(eventId, 1);

        JsonNode saved = page.get("messages").get(0);
        assertThat(saved.get("id").asLong()).isPositive();
        assertThat(saved.get("messageId").asText()).isEqualTo(sent.messageId());
        assertThat(saved.get("eventId").asLong()).isEqualTo(eventId);
        assertThat(saved.get("senderId").asLong()).isEqualTo(sender.userId());
        assertThat(saved.get("senderNickname").asText()).isEqualTo("닉네임😀");
        assertThat(saved.get("content").asText()).isEqualTo("안녕하세요\n반가워요 😀");
        assertThat(Instant.parse(saved.get("sentAt").asText())).as("마이크로초로 잘라 보내므로 DB 값과 같다")
                .isEqualTo(sent.sentAt());
        assertThat(page.get("nextBeforeId").isNull()).isTrue();
    }

    @Test
    void 최신_페이지부터_오래된_순으로_주고_beforeId_로_이전_페이지를_준다() throws Exception {
        long eventId = newEventId();
        long otherEventId = newEventId();
        WebChatUser sender = new WebChatUser(IDS.incrementAndGet(), "회원");
        for (int i = 1; i <= 7; i++) {
            repository.saveAll(List.of(message(eventId, sender, "메시지" + i)));
        }
        repository.saveAll(List.of(message(otherEventId, sender, "다른 방")));

        JsonNode first = get("/api/web-chat/rooms/" + eventId + "/messages").body(); // page-size 3
        assertThat(contents(first)).containsExactly("메시지5", "메시지6", "메시지7");

        JsonNode second = get("/api/web-chat/rooms/" + eventId + "/messages?beforeId=" + first.get("nextBeforeId").asLong()).body();
        assertThat(contents(second)).containsExactly("메시지2", "메시지3", "메시지4");

        JsonNode last = get("/api/web-chat/rooms/" + eventId + "/messages?beforeId=" + second.get("nextBeforeId").asLong()).body();
        assertThat(contents(last)).containsExactly("메시지1");
        assertThat(last.get("nextBeforeId").isNull()).as("더 없음").isTrue();

        JsonNode sized = get("/api/web-chat/rooms/" + eventId + "/messages?size=5").body();
        assertThat(contents(sized)).containsExactly("메시지3", "메시지4", "메시지5", "메시지6", "메시지7");

        JsonNode empty = get("/api/web-chat/rooms/" + newEventId() + "/messages").body();
        assertThat(empty.get("messages")).isEmpty();
        assertThat(empty.get("nextBeforeId").isNull()).isTrue();
    }

    @Test
    void 같은_메시지를_다시_저장해도_한_번만_남는다() throws Exception {
        long eventId = newEventId();
        WebChatMessage message = message(eventId, new WebChatUser(IDS.incrementAndGet(), "회원"), "한 번");

        repository.saveAll(List.of(message));
        repository.saveAll(List.of(message, message(eventId, new WebChatUser(1, "회원"), "새것"))); // 재시도로 다시 온 묶음

        assertThat(contents(get("/api/web-chat/rooms/" + eventId + "/messages").body())).containsExactly("한 번", "새것");
    }

    @Test
    void 잘못된_요청은_400_WEB_CHAT_INVALID_REQUEST() throws Exception {
        for (String path : List.of("/api/web-chat/rooms/0/messages", "/api/web-chat/rooms/abc/messages",
                "/api/web-chat/rooms/1/messages?size=0", "/api/web-chat/rooms/1/messages?size=101",
                "/api/web-chat/rooms/1/messages?beforeId=0", "/api/web-chat/rooms/1/messages?size=x")) {
            Response response = get(path);
            assertThat(response.status()).as(path).isEqualTo(400);
            assertThat(response.body().get("code").asText()).as(path).isEqualTo("WEB_CHAT_INVALID_REQUEST");
        }
    }

    @Test
    void DB_가_실패해도_보내기는_그대로_되고_복구되면_저장된다() throws Exception {
        long eventId = newEventId();
        WebChatUser sender = new WebChatUser(IDS.incrementAndGet(), "회원");
        long failuresBefore = historyWriter.failedAttemptCount();
        jdbcTemplate.execute("ALTER TABLE web_chat_messages RENAME TO web_chat_messages_broken");

        WebChatMessage sent = webChatService.send(eventId, sender, "장애 중 메시지"); // 예외 없음 = 방에는 이미 보냄

        await().atMost(Duration.ofSeconds(5)).until(() -> historyWriter.failedAttemptCount() > failuresBefore);
        Response duringOutage = get("/api/web-chat/rooms/" + eventId + "/messages");
        assertThat(duringOutage.status()).isEqualTo(503);
        assertThat(duringOutage.body().get("code").asText()).isEqualTo("WEB_CHAT_HISTORY_UNAVAILABLE");

        jdbcTemplate.execute("ALTER TABLE web_chat_messages_broken RENAME TO web_chat_messages");

        JsonNode page = awaitMessages(eventId, 1); // 재시도(200ms → 400ms …)가 저장한다
        assertThat(page.get("messages").get(0).get("messageId").asText()).isEqualTo(sent.messageId());
    }

    private JsonNode awaitMessages(long eventId, int count) {
        JsonNode[] page = new JsonNode[1];
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            page[0] = get("/api/web-chat/rooms/" + eventId + "/messages").body();
            return page[0].has("messages") && page[0].get("messages").size() == count;
        });
        return page[0];
    }

    private long newEventId() {
        long id = IDS.incrementAndGet();
        eventIds.add(id);
        return id;
    }

    private static WebChatMessage message(long eventId, WebChatUser sender, String content) {
        return new WebChatMessage(UUID.randomUUID().toString(), eventId, sender.userId(), sender.nickname(), content,
                Instant.parse("2026-11-22T03:00:00Z"));
    }

    private static List<String> contents(JsonNode page) {
        List<String> contents = new ArrayList<>();
        page.get("messages").forEach(m -> contents.add(m.get("content").asText()));
        return contents;
    }

    private Response get(String path) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), objectMapper.readTree(response.body()));
    }

    private record Response(int status, JsonNode body) {
    }
}
