package com.couponrush.webchat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 현재 시각은 Clock 으로 주입한다 (도배 제한 테스트에서 시간을 움직이려고). */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
