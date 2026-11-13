package org.fitznet.fitznetapi.dto.responses;

import java.time.Instant;
import java.util.List;

/** A stored message (contract section 6). */
public record MessageDto(
    String id,
    int seq,
    String parentId,
    String role,
    String content,
    String thinking,
    List<AttachmentDto> attachments,
    List<ToolCallDto> toolCalls,
    String status,
    ErrorDto error,
    GenerationDto generation,
    Instant createdAt,
    Instant editedAt) {

  public record AttachmentDto(String id, String kind, String mimeType, long sizeBytes, String name) {}

  /** Reserved; {@code toolCalls} is always empty in v2. */
  public record ToolCallDto(String id, String name, String arguments) {}

  public record ErrorDto(String code, String message) {}

  public record GenerationDto(
      String nodeId,
      String nodeName,
      String model,
      boolean auto,
      String presetId,
      String presetName,
      Integer contextLength,
      Integer promptTokens,
      Integer completionTokens,
      Long ttftMs,
      Long totalMs,
      Long loadMs,
      Long thinkingMs,
      Double tokensPerSecond,
      String finishReason,
      Boolean contextOverflow) {}
}
