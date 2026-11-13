package org.fitznet.fitznetapi.dto.responses;

/** Deletes return {@code { "deleted": true }}, never a void body. */
public record DeleteResponseDto(boolean deleted) {}
