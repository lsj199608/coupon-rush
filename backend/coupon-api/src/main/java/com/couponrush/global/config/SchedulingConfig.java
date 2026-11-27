package com.couponrush.global.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * @Scheduled 활성화 + ShedLock (8-5).
 * <p>
 * 서버를 여러 대 띄우면(active-active, rolling update 중의 옛 Pod·새 Pod 포함) @Scheduled 는 서버마다 돈다.
 * {@code @SchedulerLock} 이 붙은 작업은 실행 전에 shedlock 표(V10)의 그 작업 줄을 "lock_until 까지 내가 함"으로 바꾸는
 * UPDATE 를 한 번 시도하고, 성공한 서버만 실행한다. 진 서버는 기다리지 않고 이번 차례를 건너뛴다.
 * <ul>
 *   <li>저장소 = PostgreSQL(coupon_rush): 두 작업이 이미 DB 를 쓰고, Redis 장애 중에도 LLM 타임아웃 정리는 돌아야 해서</li>
 *   <li>usingDbTime: 잠금 시각을 DB 시계로 — Pod 마다 시계가 조금 달라도 판정은 한 시계로</li>
 *   <li>defaultLockAtMostFor: 작업에 따로 적지 않았을 때, 실행 중 Pod 가 죽으면 잠금이 저절로 풀리는 시간</li>
 * </ul>
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulingConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
