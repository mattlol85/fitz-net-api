package org.fitznet.fitznetapi.model;

/** How an assistant message was produced: where, with what, and how fast. */
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
    String finishReason) {}
