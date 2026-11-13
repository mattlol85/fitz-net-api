package org.fitznet.fitznetapi.model;

/** A stored target: a model, on a specific node or (null nodeId) wherever Auto routes. */
public record ModelTarget(String model, String nodeId) {}
