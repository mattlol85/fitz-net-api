package org.fitznet.fitznetapi.dto.responses;

import java.time.Instant;
import java.util.List;
import org.fitznet.fitznetapi.dto.requests.RecipeIngredientDto;

/** A full recipe: the summary fields plus ingredient rows, steps and {@code updatedAt}. */
public record RecipeDto(
    String id,
    String title,
    String chapterId,
    String imageId,
    Integer servings,
    Integer prepMinutes,
    Integer cookMinutes,
    List<String> ingredientNames,
    Instant createdAt,
    List<RecipeIngredientDto> ingredients,
    List<String> instructions,
    Instant updatedAt) {}
