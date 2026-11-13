package org.fitznet.fitznetapi.service.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.fitznet.fitznetapi.model.NodeStatus;

/**
 * A node as the catalogue and router see it. {@code address} is internal: it must never reach a
 * client (see {@code ModelCatalogDto}).
 *
 * @param status effective status (heartbeat age and in-flight work applied)
 * @param storedStatus what the last heartbeat said
 * @param stale Ollama could not be reached, so models are cached or heartbeat-only
 * @param vramTotalMb heartbeat total, else registered vramGb x 1024, else null
 * @param vramUsedMb heartbeat used, else the sum of loaded models' VRAM, else null
 * @param vramSource "heartbeat", "ollama" or null: what {@code vramUsedMb} is based on
 */
public record CatalogNode(
    String id,
    String name,
    String address,
    String os,
    NodeStatus status,
    NodeStatus storedStatus,
    Instant lastHeartbeatAt,
    int activeGenerations,
    boolean stale,
    Integer vramTotalMb,
    Integer vramUsedMb,
    String vramSource,
    List<CatalogModel> models) {

  /** Can take a chat right now: not offline and reachable. */
  public boolean routable() {
    return status != NodeStatus.OFFLINE && address != null && !address.isBlank();
  }

  public Optional<CatalogModel> model(String modelName) {
    return models.stream().filter(m -> m.name().equals(modelName)).findFirst();
  }

  /** Free GPU memory in MiB, or null when either figure is unknown. */
  public Integer freeVramMb() {
    return vramTotalMb == null || vramUsedMb == null ? null : vramTotalMb - vramUsedMb;
  }
}
