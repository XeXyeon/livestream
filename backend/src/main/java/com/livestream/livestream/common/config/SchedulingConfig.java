package com.livestream.livestream.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 유예 종료(BC-1) · 썸네일(BC-3) · 녹화 보관(REC-1) 스케줄러. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
