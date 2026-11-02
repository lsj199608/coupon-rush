package com.couponrush;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1단계 뼈대 검증: 앱이 뜨고, Postgres(pgvector)/Redis/Kafka 에 실제로 붙는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CouponRushApplicationTests {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired MockMvc mockMvc;

    @Test
    void postgres_연결되고_pgvector_확장을_쓸_수_있다() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void redis_읽기_쓰기가_된다() {
        redisTemplate.opsForValue().set("ping", "pong");

        assertThat(redisTemplate.opsForValue().get("ping")).isEqualTo("pong");
    }

    @Test
    void kafka_메시지를_보내고_받을_수_있다() throws Exception {
        String topic = "infra-check";
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("infra-check-group", null)) {
            consumer.subscribe(List.of(topic));

            kafkaTemplate.send(topic, "hello").get();

            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(consumer, topic, Duration.ofSeconds(10));
            assertThat(record.value()).isEqualTo("hello");
        }
    }

    @Test
    void 헬스체크는_인증없이_열려있고_UP_이다() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void 그_외_경로는_인증이_필요하다() throws Exception {
        mockMvc.perform(get("/api/anything"))
                .andExpect(status().isForbidden());
    }
}
