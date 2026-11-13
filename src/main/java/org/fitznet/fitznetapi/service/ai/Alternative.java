package org.fitznet.fitznetapi.service.ai;

import org.fitznet.fitznetapi.model.NodeStatus;

/**
 * Another node that has the same model, offered when a turn cannot run where it was aimed
 * (the {@code alternatives} of a turn {@code error}).
 *
 * @param status effective status of the node
 */
public record Alternative(
    String nodeId, String nodeName, String model, boolean loaded, NodeStatus status) {}
