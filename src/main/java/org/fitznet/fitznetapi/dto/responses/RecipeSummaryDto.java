package org.fitznet.fitznetapi.dto.responses;

import java.time.Instant;
import java.util.List;

/** A recipe in lists and search results (no ingredient rows, steps or photo bytes). */
public record RecipeSummaryDto(
    String id,
    String title,
    String chapterId,
    String imageId,
    Integer servings,
    Integer prepMinutes,
    Integer cookMinutes,
    List<String> ingredientNames,
    Instant createdAt) {}
