package org.fitznet.fitznetapi.dto.responses;

import java.time.Instant;

/** The rolling summary of a conversation's older turns. */
public record ChatSummaryDto(
    String text, int coversThroughSeq, String model, boolean partial, Instant createdAt) {}
