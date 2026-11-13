package org.fitznet.fitznetapi.model;

/** Why an assistant message ended in status {@code error}. */
public record MessageError(String code, String message) {}
