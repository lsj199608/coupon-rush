package com.couponrush.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @Scheduled 활성화 (재고 대조 점검 등) */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
