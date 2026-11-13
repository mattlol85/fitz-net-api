package org.fitznet.fitznetapi.dto.responses;

/** {@code POST /ai/conversations/{id}/summarize}. */
public record SummarizeResponseDto(
    ConversationDto conversation, ChatSummaryDto summary, ContextUsageDto context) {}
