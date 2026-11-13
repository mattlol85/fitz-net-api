package org.fitznet.fitznetapi.dto.responses;

import java.time.Instant;

/**
 * A preset as one caller sees it ({@code editable} is computed for them).
 *
 * @param visibility {@code private} or {@code shared}
 * @param ownerUsername the creator
 */
public record PresetDto(
    String id,
    String name,
    String description,
    String model,
    String nodeId,
    String systemPrompt,
    Double temperature,
    Integer numCtx,
    String visibility,
    String requiredPermission,
    String ownerUsername,
    boolean editable,
    Instant createdAt,
    Instant updatedAt) {}
