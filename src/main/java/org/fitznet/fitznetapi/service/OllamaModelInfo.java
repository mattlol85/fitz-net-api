package org.fitznet.fitznetapi.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a node's Ollama says about a model ({@code /api/show}): its native {@code context_length}
 * and {@code capabilities} ({@code completion}, {@code vision}, {@code thinking}, {@code tools}).
 *
 * <p>{@link #effectiveContext} is the {@code num_ctx} to run with: the native window capped at
 * {@code ollama.max-num-ctx} so a 128k-context model doesn't allocate a huge KV cache, falling back
 * to the cap if the lookup fails. Results are cached per address, model and digest for
 * {@code ollama.model-info-ttl}; failed lookups are never cached.
 */
@Component
public class OllamaModelInfo {

  private static final Logger logger = LoggerFactory.getLogger(OllamaModelInfo.class);

  /**
   * Native window (nullable when the model reports none) and capabilities (empty when the Ollama
   * version predates the field, i.e. unknown).
   */
  public record ModelDetails(Integer nativeContext, List<String> capabilities) {}

  private record Cached(ModelDetails details, long expiresAtNanos) {}

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
    ModelDetails details = details(target.address(), target.model(), null, timeout, timeout);
    return effectiveContext(details == null ? null : details.nativeContext());
  }

  /** {@code min(native, ollama.max-num-ctx)}, or the cap when the native window is unknown. */
  public int effectiveContext(Integer nativeContext) {
    return nativeContext == null ? maxNumCtx : Math.min(nativeContext, maxNumCtx);
  }

  public int maxNumCtx() {
    return maxNumCtx;
  }

  /**
   * Native context and capabilities of {@code model} on the node, from cache or {@code /api/show}.
   * {@code digest} (nullable) keys the cache so a re-pulled model is looked up again. Returns
   * {@code null} when the lookup fails.
   */
  public ModelDetails details(
      String address, String model, String digest, Duration connectTimeout, Duration readTimeout) {
    String key = key(address, model, digest);
    Cached cached = cache.get(key);
    if (cached != null && cached.expiresAtNanos() > System.nanoTime()) {
      return cached.details();
    }
    ModelDetails fetched = lookup(address, model, connectTimeout, readTimeout);
    if (fetched != null) {
      cache.put(key, new Cached(fetched, System.nanoTime() + ttl.toNanos()));
    }
    return fetched;
  }

  /** Whatever is cached for the model, even if expired; never touches the network. */
  public ModelDetails peek(String address, String model, String digest) {
    Cached cached = cache.get(key(address, model, digest));
    return cached == null ? null : cached.details();
  }

  private static String key(String address, String model, String digest) {
    return address + "|" + model + "|" + (digest == null ? "" : digest);
  }

  private ModelDetails lookup(
      String address, String model, Duration connectTimeout, Duration readTimeout) {
    try {
      JsonNode response =
          OllamaHttp.post(
              objectMapper,
              address,
              "/api/show",
              Map.of("model", model),
              connectTimeout,
              readTimeout);
      Integer nativeContext = null;
      Iterator<Map.Entry<String, JsonNode>> fields =
          response.path("model_info").properties().iterator();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        if (field.getKey().endsWith(".context_length") && field.getValue().canConvertToInt()) {
          nativeContext = field.getValue().asInt();
          break;
        }
      }
      List<String> capabilities = new ArrayList<>();
      for (JsonNode capability : response.path("capabilities")) {
        capabilities.add(capability.asText());
      }
      return new ModelDetails(nativeContext, List.copyOf(capabilities));
    } catch (Exception ex) {
      logger.warn(
          "Could not read model details for {} at {}: {}", model, address, ex.getMessage());
      return null;
    }
  }
}
