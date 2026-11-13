package org.fitznet.fitznetapi.service.ai;

import java.time.Instant;
import java.util.List;

/**
 * One model on one node, as the catalogue sees it. Detail fields are {@code null} (and
 * {@code capabilities} empty) when Ollama could not be asked and nothing was cached.
 *
 * @param contextLength effective window, {@code min(native, ollama.max-num-ctx)}; null when the
 *     native window is unknown
 * @param loadedInOllama listed by the node's {@code /api/ps}
 * @param warming a warm-up for it is in flight on this API
 */
public record CatalogModel(
    String name,
    String family,
    String parameterSize,
    String quantization,
    Long sizeBytes,
    Integer contextLength,
    Integer maxContextLength,
    List<String> capabilities,
    boolean loadedInOllama,
    boolean warming,
    Instant loadedUntil,
    Long vramMb) {

  /** Loaded, or about to be: routing treats both as warm. */
  public boolean loaded() {
    return loadedInOllama || warming;
  }

  public boolean hasCapability(String capability) {
    return capabilities.contains(capability);
  }
}
