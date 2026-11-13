package org.fitznet.fitznetapi.service.ai;

import java.time.Duration;
import java.time.Instant;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place that turns a node's stored heartbeat status into the status everything exposes and
 * routes on:
 *
 * <pre>
 * OFFLINE  stored status is OFFLINE, or lastHeartbeatAt is null or older than ai.node.stale-after
 * BUSY     otherwise, if stored status is BUSY or the API has at least one generation in flight
 * ONLINE   otherwise
 * </pre>
 */
@Component
public class NodeStatusResolver {

  private final NodeActivityTracker activityTracker;
  private final Duration staleAfter;

  public NodeStatusResolver(
      NodeActivityTracker activityTracker,
      @Value("${ai.node.stale-after:5m}") Duration staleAfter) {
    this.activityTracker = activityTracker;
    this.staleAfter = staleAfter;
  }

  public NodeStatus effective(AiNode node) {
    return effective(node, Instant.now());
  }

  public NodeStatus effective(AiNode node, Instant now) {
    NodeStatus stored = node.getStatus();
    Instant lastHeartbeat = node.getLastHeartbeatAt();
    if (stored == NodeStatus.OFFLINE
        || lastHeartbeat == null
        || lastHeartbeat.isBefore(now.minus(staleAfter))) {
      return NodeStatus.OFFLINE;
    }
    if (stored == NodeStatus.BUSY || activityTracker.active(node.getId()) > 0) {
      return NodeStatus.BUSY;
    }
    return NodeStatus.ONLINE;
  }

  public Duration staleAfter() {
    return staleAfter;
  }
}
