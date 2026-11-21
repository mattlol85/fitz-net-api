package org.fitznet.fitznetapi.dto.responses;

import java.util.List;

/**
 * {@code GET /recipes}: matches newest first, at most 500; {@code total} counts every match.
 */
public record RecipeListDto(List<RecipeSummaryDto> items, long total) {}
