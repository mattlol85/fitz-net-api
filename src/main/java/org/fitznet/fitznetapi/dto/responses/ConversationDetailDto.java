package org.fitznet.fitznetapi.dto.responses;

import java.time.Instant;
import java.util.List;
import org.fitznet.fitznetapi.dto.ConversationSettingsDto;
import org.fitznet.fitznetapi.dto.ModelTargetDto;

/**
 * {@code GET /ai/conversations/{id}}: the conversation plus its summary, the context usage for its
 * current target ({@code null} when that cannot be resolved right now) and its messages.
 */
public record ConversationDetailDto(
    String id,
    String title,
    String titleSource,
    boolean pinned,
    ModelTargetDto target,
    ConversationSettingsDto settings,
    int messageCount,
    Instant createdAt,
    Instant updatedAt,
    ChatSummaryDto summary,
    ContextUsageDto context,
    List<MessageDto> messages) {}
