package org.fitznet.fitznetapi.model;

/**
 * How an assistant message was produced: where, with what, and how fast. {@code contextOverflow}
 * is the same flag the {@code done} event carried (null unless the turn completed).
 */
public record MessageGeneration(
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
