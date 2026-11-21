package org.fitznet.fitznetapi.dto.responses;

/** The raw bytes of a stored image with their content type. Not a JSON body. */
public record ImageContentDto(String mimeType, byte[] data) {}
