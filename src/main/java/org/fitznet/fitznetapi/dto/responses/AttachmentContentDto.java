package org.fitznet.fitznetapi.dto.responses;

/** The raw bytes of an attachment with their content type. Not a JSON body. */
public record AttachmentContentDto(String mimeType, byte[] data) {}
