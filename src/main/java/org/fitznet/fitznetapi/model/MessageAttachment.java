package org.fitznet.fitznetapi.model;

/** A reference to an uploaded attachment on a message (part 3; always empty until then). */
public record MessageAttachment(String id, String kind, String mimeType, long sizeBytes, String name) {}
