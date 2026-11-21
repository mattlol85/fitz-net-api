package org.fitznet.fitznetapi.dto.responses;

import java.util.List;

/** {@code GET /recipes/ingredients}: the back-of-book index, entries A to Z. */
public record IngredientIndexDto(List<IngredientIndexEntryDto> entries) {}
