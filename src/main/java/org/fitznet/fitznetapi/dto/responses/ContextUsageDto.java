package org.fitznet.fitznetapi.dto.responses;

/**
 * How the conversation would fit the window of the resolved model (contract section 8).
 *
 * @param nodeId the node the target resolved to
 * @param usedTokens estimate of what would be sent next turn (system + summary + included history)
 * @param totalTokens estimate of the whole stored history
 * @param fits {@code trimmedCount == 0}
 */
public record ContextUsageDto(
    String model,
    String nodeId,
    int contextLength,
    int usedTokens,
    int totalTokens,
    boolean fits,
    int trimmedCount,
    boolean summaryActive) {}
