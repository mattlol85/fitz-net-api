package org.fitznet.fitznetapi.dto.responses;

/** An uploaded attachment (never its bytes; those come from {@code GET /ai/attachments/{id}}). */
public record AttachmentDto(String id, String kind, String mimeType, long sizeBytes, String name) {}
