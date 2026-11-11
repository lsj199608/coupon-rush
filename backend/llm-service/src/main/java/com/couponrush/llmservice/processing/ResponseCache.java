package com.couponrush.llmservice.processing;

import com.couponrush.llm.contract.EventCopyCandidate;
import com.couponrush.llm.contract.EventCopyInput;
import com.couponrush.llm.contract.EventSearchFilter;
import com.couponrush.llm.contract.EventSearchInput;
import com.couponrush.llmservice.LlmServiceProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 응답 캐시 (Redis) — {@code llm:cache:{sha256(제공자·모델·프롬프트 버전 + 정규화한 입력)}}, TTL 24시간.
 * 키를 해시로 만드는 이유: 입력(최대 200자 한국어)을 키에 그대로 넣지 않기 위해 (길이·특수문자·관리자 입력이 키 이름에 노출).
 * 성공(COMPLETED) 결과만 저장한다 — 실패를 캐시하면 일시 장애가 24시간 지속된다.
 * Redis 오류는 캐시 없이 진행한다 (캐시는 비용 절감 장치일 뿐, 실패 사유가 되면 안 된다).
 */
@Component
public class ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(ResponseCache.class);

    /**
     * 캐시에 두는 값: 결과 내용(문구 후보 또는 검색 조건 — 기능에 맞는 하나)과 그때 답한 모델.
     * searchFilter 는 나중에 추가한 필드 — 예전에 저장된 문구 캐시는 없는 필드로 읽혀 null 이 된다 (그대로 호환).
     */
    public record Entry(List<EventCopyCandidate> candidates, EventSearchFilter searchFilter, String model) {
    }

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final LlmServiceProperties.Cache config;

    public ResponseCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, LlmServiceProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.config = properties.cache();
    }

    public boolean enabled() {
        return config.enabled();
    }

    public Optional<Entry> find(String key) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, Entry.class));
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("응답 캐시 읽기 실패 — 캐시 없이 진행: key={}", key, e);
            return Optional.empty();
        }
    }

    public void save(String key, Entry entry) {
        if (!config.enabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(entry), config.ttl());
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("응답 캐시 저장 실패 — 무시: key={}", key, e);
        }
    }

    /**
     * 같은 출력을 낼 조건이 같으면 같은 키. 키워드는 앞뒤 공백 제거·연속 공백 하나로·소문자 (사소한 차이로 캐시를 놓치지 않게).
     */
    public static String key(String clientIdentity, EventCopyInput input) {
        String normalizedKeywords = input.keywords().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String material = String.join("\n",
                clientIdentity,
                "EVENT_COPY",
                normalizedKeywords,
                input.tone().name(),
                String.valueOf(input.totalQuantity()),
                String.valueOf(input.issueStartAt()),
                String.valueOf(input.issueEndAt()));
        return "llm:cache:" + sha256(material);
    }

    /**
     * 검색 조건 캐시 키. 질문은 같은 방식으로 정규화하고, <b>기준 날짜</b>를 넣는다 — "이번 주"·"내일"의 뜻이 날짜마다 달라서.
     * 같은 날 같은 질문은 몇 번을 물어도 LLM 을 한 번만 부른다 (검색은 문구 생성보다 훨씬 자주 불린다).
     */
    public static String key(String clientIdentity, EventSearchInput input, LocalDate today) {
        String normalizedQuery = input.query().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String material = String.join("\n", clientIdentity, "EVENT_SEARCH", normalizedQuery, today.toString());
        return "llm:cache:" + sha256(material);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
