package org.fitznet.fitznetapi.dto.responses;

/** {@code DELETE /recipes/{id}} returns the id and {@code deleted: true}, never a void body. */
public record DeleteRecipeResponseDto(String id, boolean deleted) {}
