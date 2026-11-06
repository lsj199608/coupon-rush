package com.couponrush.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 현재 시각을 Bean 으로 주입받아, 테스트에서 시간을 고정할 수 있게 한다 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
