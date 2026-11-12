package com.livestream.livestream.common.config;

import com.livestream.livestream.common.security.CurrentMember;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	static {
		// @CurrentMember 파라미터는 요청 값이 아니므로 Swagger 문서에서 뺀다.
		SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentMember.class);
	}
}
