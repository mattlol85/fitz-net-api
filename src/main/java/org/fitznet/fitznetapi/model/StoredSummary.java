package org.fitznet.fitznetapi.model;

import java.time.Instant;

/**
 * A rolling summary of the oldest turns of a conversation, kept on the conversation. It covers every
 * message with {@code seq <= coversThroughSeq}.
 *
 * @param partial the covered range did not fit the window, so only its newest part was summarised
 */
public record StoredSummary(
    String text,
    int coversThroughSeq,
    int tokenEstimate,
    String model,
    String nodeId,
    boolean partial,
    Instant createdAt) {}
