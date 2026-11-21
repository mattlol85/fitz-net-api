package org.fitznet.fitznetapi.dto.responses;

import java.util.List;

/** One normalized ingredient name and the recipes that use it (A to Z by title). */
public record IngredientIndexEntryDto(String name, List<RecipeRefDto> recipes) {}
