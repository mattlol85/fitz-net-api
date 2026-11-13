package org.fitznet.fitznetapi.dto.responses;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /ai/models} (contract section 3): every registered node with its models, plus one
 * "Auto" row per distinct model name. A node's address is internal and never appears here.
 */
public record ModelCatalogDto(Instant generatedAt, List<NodeEntry> nodes, List<AutoModelEntry> models) {

  /** @param status effective status: ONLINE, BUSY or OFFLINE */
  public record NodeEntry(
      String id,
      String name,
      String status,
      String os,
      Instant lastHeartbeatAt,
      int activeGenerations,
      boolean catalogStale,
      Vram vram,
      List<ModelEntry> models) {}

  /** @param source "heartbeat", "ollama" or null: what {@code usedMb} is based on */
  public record Vram(Integer totalMb, Integer usedMb, String source) {}

  public record ModelEntry(
      String name,
      String family,
      String parameterSize,
      String quantization,
      Long sizeBytes,
      Integer contextLength,
      Integer maxContextLength,
      List<String> capabilities,
      boolean loaded,
      Instant loadedUntil,
      Long vramMb) {}

  /** @param autoNodeId where Auto would route right now; details are that node's copy */
  public record AutoModelEntry(
      String name,
      List<String> nodeIds,
      List<String> loadedOn,
      String autoNodeId,
      String parameterSize,
      String quantization,
      Integer contextLength,
      List<String> capabilities) {}
}
