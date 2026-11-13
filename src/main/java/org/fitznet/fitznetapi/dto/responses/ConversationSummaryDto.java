package org.fitznet.fitznetapi.dto.responses;

import java.time.Instant;
import org.fitznet.fitznetapi.dto.ConversationSettingsDto;
import org.fitznet.fitznetapi.dto.ModelTargetDto;

/**
 * A list row: a {@link ConversationDto} plus, for a message-content search hit, a snippet around the
 * match (at most 160 characters).
 */
public record ConversationSummaryDto(
    String id,
    String title,
    String titleSource,
    boolean pinned,
    ModelTargetDto target,
    ConversationSettingsDto settings,
    int messageCount,
    Instant createdAt,
    Instant updatedAt,
    String matchSnippet) {}
