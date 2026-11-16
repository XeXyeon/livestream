package com.livestream.livestream.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 비동기 실행기 (DESIGN §4.18). 클립 처리기 {@code clipExecutor} 는 스레드 1개로 하나씩 돈다(시연 서버 CPU · 디스크 보호).
 * 녹화 병합(RecordingMergeService 자체 단일 스레드)과는 분리해, 긴 병합이 클립을 막거나 그 반대가 되지 않게 한다.
 * <p>종료 때는 기다리지 않고 인터럽트한다(waitForTasksToCompleteOnShutdown=false). 끊긴 클립은 PROCESSING 으로 남아
 * 재시작 때 {@code ClipProcessingService.resumePending} 이 다시 줄 세운다.
 * <p>Executor 빈이 하나라도 있으면 Spring Boot 가 기본 {@code applicationTaskExecutor} 를 만들지 않으므로,
 * application.yaml 의 {@code spring.task.execution.mode: force} 로 기본 실행기를 그대로 유지한다.
 */
@Configuration
public class AsyncConfig {

	public static final String CLIP_EXECUTOR = "clipExecutor";

	@Bean(name = CLIP_EXECUTOR)
	ThreadPoolTaskExecutor clipExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setThreadNamePrefix("clip-");
		executor.setDaemon(true);
		executor.setWaitForTasksToCompleteOnShutdown(false);
		return executor;
	}
}
