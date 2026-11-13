package org.fitznet.fitznetapi.dto.responses;

import java.time.Instant;
import org.fitznet.fitznetapi.dto.ModelTargetDto;

/** {@code GET/PUT /ai/preferences}. A user who never saved anything gets nulls and no timestamp. */
public record ChatPreferencesDto(ModelTargetDto lastTarget, String lastPresetId, Instant updatedAt) {}
