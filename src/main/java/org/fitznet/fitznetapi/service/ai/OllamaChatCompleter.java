package org.fitznet.fitznetapi.service.ai;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fitznet.fitznetapi.service.OllamaHttp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Blocking, non-streaming {@code /api/chat} for short side jobs (conversation titles, summaries).
 * Counts as an in-flight generation on the node while it runs. Prompt and reply are never logged.
 */
@Component
public class OllamaChatCompleter {

  private final ObjectMapper objectMapper;
  private final NodeActivityTracker activityTracker;
  private final Duration connectTimeout;
  private final String keepAlive;

  public OllamaChatCompleter(
      ObjectMapper objectMapper,
      NodeActivityTracker activityTracker,
      @Value("${ollama.connect-timeout:5s}") Duration connectTimeout,
      @Value("${ollama.keep-alive:30m}") String keepAlive) {
    this.objectMapper = objectMapper;
    this.activityTracker = activityTracker;
    this.connectTimeout = connectTimeout;
    this.keepAlive = keepAlive;
  }

  /**
   * @param systemPrompt the instruction, sent as a system message
   * @param numPredict upper bound on generated tokens
   * @param readTimeout how long to wait for the node (the call blocks until the reply is complete)
   * @param thinkOff send {@code think: false}; only for models that report the {@code thinking}
   *     capability, since Ollama rejects the field for others
   * @return the reply text, possibly empty
   * @throws IOException when the node cannot be reached, times out or answers with an error
   */
  public String complete(
      RoutedTarget target,
      String systemPrompt,
      List<TurnMessage> messages,
      int numCtx,
      int numPredict,
      Duration readTimeout,
      boolean thinkOff)
      throws IOException {
    List<Map<String, Object>> wire = new ArrayList<>();
    wire.add(Map.of("role", "system", "content", systemPrompt));
    for (TurnMessage message : messages) {
      wire.add(Map.of("role", message.role(), "content", message.content()));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", target.model());
    body.put("messages", wire);
    body.put("stream", false);
    body.put("keep_alive", keepAlive);
    body.put("options", Map.of("num_ctx", numCtx, "num_predict", numPredict));
    if (thinkOff) {
      body.put("think", false);
    }
    try (NodeActivityTracker.Generation ignored = activityTracker.begin(target.nodeId())) {
      JsonNode response =
          OllamaHttp.post(
              objectMapper, target.address(), "/api/chat", body, connectTimeout, readTimeout);
      if (response.hasNonNull("error")) {
        throw new IOException("Node returned an error");
      }
      return response.path("message").path("content").asText("");
    }
  }
}
