package org.fitznet.fitznetapi.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A closed browser is noticed on the second keep-alive write (the first lands in the socket
 * buffer), so the default poll interval is 1 s to keep cancel detection near 2 s.
 */
@SpringBootTest
@Import(EmbeddedMongoTestConfiguration.class)
class CancelPollIntervalDefaultTest {

  @Autowired private OllamaTurnStreamer streamer;

  @Test
  void shouldDefaultTheCancelPollIntervalToOneSecondInTheEngine() {
    assertEquals(Duration.ofSeconds(1), ReflectionTestUtils.getField(streamer, "cancelPollInterval"));
  }

  @Test
  void shouldDefaultTheCancelPollIntervalToOneSecondInTheMainProperties() throws Exception {
    String properties = Files.readString(Path.of("src/main/resources/application.properties"));

    assertTrue(
        properties.contains("ai.chat.cancel-poll-interval=${AI_CHAT_CANCEL_POLL_INTERVAL:1s}"),
        "application.properties does not default ai.chat.cancel-poll-interval to 1s");
  }
}
