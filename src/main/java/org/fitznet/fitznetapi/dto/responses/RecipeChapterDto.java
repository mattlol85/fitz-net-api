package org.fitznet.fitznetapi.dto.responses;

/** A chapter of the caller's book with the number of recipes filed under it. */
public record RecipeChapterDto(String id, String name, String tabColor, long recipeCount) {}
