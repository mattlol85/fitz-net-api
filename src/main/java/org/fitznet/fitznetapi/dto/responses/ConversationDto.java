package org.fitznet.fitznetapi.dto.responses;

import java.time.Instant;
import org.fitznet.fitznetapi.dto.ConversationSettingsDto;
import org.fitznet.fitznetapi.dto.ModelTargetDto;

/** A conversation without its messages (contract section 6). */
public record ConversationDto(
    String id,
    String title,
    String titleSource,
    boolean pinned,
    ModelTargetDto target,
    ConversationSettingsDto settings,
    int messageCount,
    Instant createdAt,
    Instant updatedAt) {}
