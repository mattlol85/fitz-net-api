package org.fitznet.fitznetapi.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Executor for the AI layer's background work: parallel per-node catalogue fetches and model
 * warm-ups. Virtual threads, since every task is a blocking HTTP call to a node's Ollama. Submit
 * work through {@code MdcPropagation.wrap} so the correlation id follows it.
 */
@Configuration
public class AiExecutorConfig {

  public static final String AI_BACKGROUND_EXECUTOR = "aiBackgroundExecutor";

  @Bean(name = AI_BACKGROUND_EXECUTOR, destroyMethod = "shutdownNow")
  public ExecutorService aiBackgroundExecutor() {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("ai-background-", 0).factory());
  }
}
