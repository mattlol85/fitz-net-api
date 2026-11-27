package org.fitznet.fitznetapi.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Nodes are people's own PCs and a request's {@code keep_alive} overrides the node's own setting,
 * so a loaded model holds their VRAM/RAM for as long as the API asks. Both values stay short, and
 * a warm-up (which loads a model before any message is sent) must not use the longer chat value.
 */
@SpringBootTest
@Import(EmbeddedMongoTestConfiguration.class)
class KeepAliveDefaultsTest {

  @Autowired private WarmUpService warmUpService;
  @Autowired private OllamaTurnStreamer streamer;

  @Test
  void shouldWarmUpWithItsOwnShortKeepAlive() {
    assertEquals("5m", ReflectionTestUtils.getField(warmUpService, "warmUpKeepAlive"));
  }

  @Test
  void shouldKeepAChatModelLoadedForTenMinutes() {
    assertEquals("10m", ReflectionTestUtils.getField(streamer, "keepAlive"));
  }

  @Test
  void shouldDefaultBothKeepAlivesInTheMainProperties() throws Exception {
    String properties = Files.readString(Path.of("src/main/resources/application.properties"));

    assertTrue(
        properties.contains("ollama.keep-alive=${OLLAMA_KEEP_ALIVE:10m}"),
        "application.properties does not default ollama.keep-alive to 10m");
    assertTrue(
        properties.contains("ollama.warmup-keep-alive=${OLLAMA_WARMUP_KEEP_ALIVE:5m}"),
        "application.properties does not default ollama.warmup-keep-alive to 5m");
  }
}
