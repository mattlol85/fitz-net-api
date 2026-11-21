package org.fitznet.fitznetapi.dto.responses;

import java.time.Instant;

/**
 * An uploaded recipe photo (never its bytes; those come from {@code GET /recipe-images/{id}}).
 * {@code width} and {@code height} are null when the image header could not be read.
 */
public record RecipeImageDto(
    String id, String mimeType, long sizeBytes, Integer width, Integer height, Instant createdAt) {}
