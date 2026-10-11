package org.fitznet.fitznetapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Works out the context window ({@code num_ctx}) to run a model with: the model's own
 * {@code context_length} from Ollama {@code /api/show}, capped at {@code ollama.max-num-ctx} so a
 * 128k-context model doesn't allocate a huge KV cache. Falls back to the cap if the lookup fails.
 */
@Component
public class OllamaModelInfo {

  private static final Logger logger = LoggerFactory.getLogger(OllamaModelInfo.class);

  private record Cached(int contextLength, long expiresAtNanos) {}

  private final ObjectMapper objectMapper;
  private final Duration timeout;
  private final int maxNumCtx;
  private final Duration ttl;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public OllamaModelInfo(
      ObjectMapper objectMapper,
      @Value("${ollama.connect-timeout:5s}") Duration timeout,
      @Value("${ollama.max-num-ctx:8192}") int maxNumCtx,
      @Value("${ollama.model-info-ttl:10m}") Duration ttl) {
    this.objectMapper = objectMapper;
    this.timeout = timeout;
    this.maxNumCtx = maxNumCtx;
    this.ttl = ttl;
  }

  /** The {@code num_ctx} to send to Ollama and report to the client. */
  public int effectiveContext(ChatTarget target) {
    String key = target.address() + "|" + target.model();
    Cached cached = cache.get(key);
    if (cached != null && cached.expiresAtNanos() > System.nanoTime()) {
      return cached.contextLength();
    }
    Integer modelMax = lookup(target);
    if (modelMax == null) {
      return maxNumCtx; // not cached, so the next turn retries
    }
    int effective = Math.min(modelMax, maxNumCtx);
    cache.put(key, new Cached(effective, System.nanoTime() + ttl.toNanos()));
    return effective;
  }

  private Integer lookup(ChatTarget target) {
    HttpURLConnection connection = null;
    try {
      connection =
          (HttpURLConnection)
              URI.create("http://" + target.address() + "/api/show").toURL().openConnection();
      connection.setConnectTimeout((int) timeout.toMillis());
      connection.setReadTimeout((int) timeout.toMillis());
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setDoOutput(true);
      try (OutputStream body = connection.getOutputStream()) {
        objectMapper.writeValue(body, Map.of("model", target.model()));
      }
      if (connection.getResponseCode() / 100 != 2) {
        return null;
      }
      JsonNode modelInfo = objectMapper.readTree(connection.getInputStream()).path("model_info");
      Iterator<Map.Entry<String, JsonNode>> fields = modelInfo.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        if (field.getKey().endsWith(".context_length") && field.getValue().canConvertToInt()) {
          return field.getValue().asInt();
        }
      }
      return null;
    } catch (Exception ex) {
      logger.warn(
          "Could not read context length for {} on node {}: {}",
          target.model(),
          target.nodeId(),
          ex.getMessage());
      return null;
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }
}
