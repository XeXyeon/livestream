package com.livestream.livestream.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** {@code app.seed.enabled=true} 일 때 기동하면서 시연 시드를 넣는다 (DESIGN §5, OPS-4). */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SeedRunner implements ApplicationRunner {

	private final SeedService seedService;

	@Override
	public void run(ApplicationArguments args) {
		seedService.seed();
	}
}
