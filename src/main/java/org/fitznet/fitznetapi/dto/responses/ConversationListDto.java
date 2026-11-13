package org.fitznet.fitznetapi.dto.responses;

import java.util.List;

/** {@code GET /ai/conversations}. */
public record ConversationListDto(List<ConversationSummaryDto> items, boolean hasMore) {}
