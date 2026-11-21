package org.fitznet.fitznetapi.dto.responses;

import java.time.Instant;
import java.util.List;

/**
 * The caller's recipe book: chapters in book order, and {@code recipeCount} over every recipe
 * (including Unsorted ones, whose {@code chapterId} is null).
 */
public record RecipeBookDto(
    String id,
    String title,
    String coverColor,
    List<RecipeChapterDto> chapters,
    long recipeCount,
    Instant createdAt) {}
