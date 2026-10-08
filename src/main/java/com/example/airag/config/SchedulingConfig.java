package com.example.airag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开定时任务:目前用于清理限流器的过期窗口。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
