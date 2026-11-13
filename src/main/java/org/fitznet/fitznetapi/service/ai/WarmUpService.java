package org.fitznet.fitznetapi.service.ai;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.fitznet.fitznetapi.config.AiExecutorConfig;
import org.fitznet.fitznetapi.dto.responses.WarmUpResponseDto;
import org.fitznet.fitznetapi.service.OllamaHttp;
import org.fitznet.fitznetapi.tracing.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Preloads a model so the first token does not wait for it to load (contract section 5). Resolves
 * the target with the routing rules, answers immediately, and when the model is not in memory
 * sends Ollama an empty {@code /api/generate} with {@code keep_alive} on a background thread.
 * Concurrent warm-ups of the same node + model share one in-flight request; while it runs the
 * model counts as loaded for routing and the catalogue.
 */
@Service
public class WarmUpService {

  public static final String STATE_LOADED = "loaded";
  public static final String STATE_LOADING = "loading";
  public static final String STATE_UNAVAILABLE = "unavailable";

  private static final Logger logger = LoggerFactory.getLogger(WarmUpService.class);

  private final ModelRouter router;
  private final WarmUpRegistry registry;
  private final ModelCatalogService catalogService;
  private final ObjectMapper objectMapper;
  private final ExecutorService executor;
  private final Duration connectTimeout;
  private final Duration readTimeout;
  private final String keepAlive;

  public WarmUpService(
      ModelRouter router,
      WarmUpRegistry registry,
      ModelCatalogService catalogService,
      ObjectMapper objectMapper,
      @Qualifier(AiExecutorConfig.AI_BACKGROUND_EXECUTOR) ExecutorService executor,
      @Value("${ollama.connect-timeout:5s}") Duration connectTimeout,
      @Value("${ollama.read-timeout:180s}") Duration readTimeout,
      @Value("${ollama.keep-alive:30m}") String keepAlive) {
    this.router = router;
    this.registry = registry;
    this.catalogService = catalogService;
    this.objectMapper = objectMapper;
    this.executor = executor;
    this.connectTimeout = connectTimeout;
    this.readTimeout = readTimeout;
    this.keepAlive = keepAlive;
  }

  /** {@code nodeId == null} warms wherever Auto would route right now. */
  public WarmUpResponseDto warmUp(String model, String nodeId) {
    RouteOutcome outcome = router.route(model, nodeId, Set.of());
    if (outcome instanceof RouteOutcome.Failed failed) {
      return new WarmUpResponseDto(
          STATE_UNAVAILABLE,
          nodeId == null || nodeId.isBlank() ? null : nodeId,
          null,
          model,
          failed.message());
    }
    RoutedTarget target = ((RouteOutcome.Routed) outcome).target();
    if (registry.isInFlight(target.nodeId(), target.model())) {
      return response(STATE_LOADING, target);
    }
    if (target.loaded()) {
      return response(STATE_LOADED, target);
    }
    if (registry.tryBegin(target.nodeId(), target.model())) {
      try {
        executor.execute(MdcPropagation.wrap(() -> preload(target)));
      } catch (RuntimeException ex) {
        registry.end(target.nodeId(), target.model());
        logger.warn("Could not start warm-up of {} on node {}: {}", target.model(), target.nodeId(), ex.toString());
        return new WarmUpResponseDto(
            STATE_UNAVAILABLE,
            target.nodeId(),
            target.nodeName(),
            target.model(),
            "Could not start the warm-up.");
      }
    }
    return response(STATE_LOADING, target);
  }

  private void preload(RoutedTarget target) {
    long started = System.nanoTime();
    try {
      OllamaHttp.post(
          objectMapper,
          target.address(),
          "/api/generate",
          Map.of("model", target.model(), "keep_alive", keepAlive),
          connectTimeout,
          readTimeout);
      logger.info(
          "Warmed {} on node {} in {}ms",
          target.model(),
          target.nodeId(),
          (System.nanoTime() - started) / 1_000_000);
    } catch (Exception ex) {
      logger.warn(
          "Warm-up of {} on node {} failed after {}ms: {}",
          target.model(),
          target.nodeId(),
          (System.nanoTime() - started) / 1_000_000,
          ex.getMessage());
    } finally {
      registry.end(target.nodeId(), target.model());
      catalogService.invalidateLoaded(target.nodeId());
    }
  }

  private static WarmUpResponseDto response(String state, RoutedTarget target) {
    return new WarmUpResponseDto(
        state, target.nodeId(), target.nodeName(), target.model(), null);
  }
}
