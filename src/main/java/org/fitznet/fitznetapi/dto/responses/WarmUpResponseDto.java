package org.fitznet.fitznetapi.dto.responses;

/**
 * {@code POST /ai/warmup} answer.
 *
 * @param state {@code loaded} (already in memory), {@code loading} (a preload was started or is in
 *     flight) or {@code unavailable} (no node can serve it right now)
 * @param nodeId the node chosen; may be null when {@code unavailable}
 * @param message why, when {@code unavailable}; otherwise null
 */
public record WarmUpResponseDto(
    String state, String nodeId, String nodeName, String model, String message) {}
