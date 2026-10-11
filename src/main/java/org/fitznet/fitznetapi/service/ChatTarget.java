package org.fitznet.fitznetapi.service;

/** A resolved chat destination: the node, its reachable host:port, and the model to run. */
public record ChatTarget(String nodeId, String address, String model) {}
