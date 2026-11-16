package com.livestream.livestream;

import static org.assertj.core.api.Assertions.assertThat;

import com.livestream.livestream.common.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AppPropertiesTest {

	@Autowired
	AppProperties props;

	@Test
	void application_yaml의_app_기본값이_바인딩된다() {
		assertThat(props.grid().threshold()).isEqualTo(100);
		assertThat(props.broadcast().disconnectGraceSeconds()).isEqualTo(60);
		assertThat(props.broadcast().endBlockSeconds()).isEqualTo(30);
		assertThat(props.broadcast().viewerBroadcastIntervalSeconds()).isEqualTo(5);
		assertThat(props.thumbnail().intervalSeconds()).isEqualTo(30);
		assertThat(props.recording().retentionDays()).isEqualTo(30);
		assertThat(props.recording().dir()).isEqualTo("/recordings");
		assertThat(props.uploads().dir()).isEqualTo("/uploads");
		assertThat(props.mediamtx().rtspUrl()).isEqualTo("rtsp://mediamtx:8554");
		assertThat(props.mediamtx().playbackUrl()).isEqualTo("http://mediamtx:9996");
		assertThat(props.clip().minSeconds()).isEqualTo(5);
		assertThat(props.clip().maxSeconds()).isEqualTo(60);
		assertThat(props.clip().timeout()).isEqualTo(java.time.Duration.ofMinutes(5));
		assertThat(props.jwt().accessMinutes()).isEqualTo(15);
		assertThat(props.jwt().refreshDays()).isEqualTo(14);
		assertThat(props.jwt().refreshRotationGraceSeconds()).isEqualTo(10);
		assertThat(props.jwt().secret()).hasSizeGreaterThanOrEqualTo(32);
		assertThat(props.seed().enabled()).isFalse();
		assertThat(props.publicUrl()).isEqualTo("http://localhost");
	}
}
