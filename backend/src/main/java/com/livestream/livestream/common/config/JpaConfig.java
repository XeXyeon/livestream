package com.livestream.livestream.common.config;

import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing
public class JpaConfig {

	/** DB에는 UTC로 저장한다(DESIGN §4.5). JDBC URL의 serverTimezone과 무관하게 Instant를 UTC로 변환한다. */
	@Bean
	HibernatePropertiesCustomizer utcTimeZoneCustomizer() {
		return props -> props.put("hibernate.jdbc.time_zone", "UTC");
	}
}
