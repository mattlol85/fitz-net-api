package org.fitznet.fitznetapi.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.junit.jupiter.api.Test;

class NodeStatusResolverTest {

  private static final Instant NOW = Instant.parse("2026-11-12T18:00:00Z");

  private final NodeActivityTracker tracker = new NodeActivityTracker();
  private final NodeStatusResolver resolver =
      new NodeStatusResolver(tracker, Duration.ofMinutes(5));

  private AiNode node(NodeStatus stored, Instant heartbeat) {
    return AiNode.builder().id("n1").name("rig").status(stored).lastHeartbeatAt(heartbeat).build();
  }

  @Test
  void shouldBeOnlineWhenHeartbeatIsFreshAndNothingIsRunning() {
    assertEquals(
        NodeStatus.ONLINE,
        resolver.effective(node(NodeStatus.ONLINE, NOW.minusSeconds(30)), NOW));
  }

  @Test
  void shouldBeOfflineWhenHeartbeatIsOlderThanStaleAfter() {
    assertEquals(
        NodeStatus.OFFLINE,
        resolver.effective(node(NodeStatus.ONLINE, NOW.minus(Duration.ofMinutes(6))), NOW));
  }

  @Test
  void shouldStayOnlineExactlyAtTheStaleBoundary() {
    assertEquals(
        NodeStatus.ONLINE,
        resolver.effective(node(NodeStatus.ONLINE, NOW.minus(Duration.ofMinutes(5))), NOW));
  }

  @Test
  void shouldBeOfflineWhenThereHasNeverBeenAHeartbeat() {
    assertEquals(NodeStatus.OFFLINE, resolver.effective(node(NodeStatus.ONLINE, null), NOW));
  }

  @Test
  void shouldBeOfflineWhenStoredStatusIsOfflineEvenWithFreshHeartbeat() {
    assertEquals(
        NodeStatus.OFFLINE, resolver.effective(node(NodeStatus.OFFLINE, NOW.minusSeconds(1)), NOW));
  }

  @Test
  void shouldBeBusyWhenStoredStatusIsBusy() {
    assertEquals(
        NodeStatus.BUSY, resolver.effective(node(NodeStatus.BUSY, NOW.minusSeconds(1)), NOW));
  }

  @Test
  void shouldBeBusyWhileAGenerationIsInFlightAndOnlineAgainAfterwards() {
    AiNode node = node(NodeStatus.ONLINE, NOW.minusSeconds(1));

    try (NodeActivityTracker.Generation ignored = tracker.begin("n1")) {
      assertEquals(NodeStatus.BUSY, resolver.effective(node, NOW));
      assertEquals(1, tracker.active("n1"));
    }

    assertEquals(NodeStatus.ONLINE, resolver.effective(node, NOW));
    assertEquals(0, tracker.active("n1"));
  }

  @Test
  void shouldStayOfflineEvenWhenAGenerationIsInFlight() {
    try (NodeActivityTracker.Generation ignored = tracker.begin("n1")) {
      assertEquals(
          NodeStatus.OFFLINE,
          resolver.effective(node(NodeStatus.ONLINE, NOW.minus(Duration.ofHours(1))), NOW));
    }
  }

  @Test
  void shouldCountConcurrentGenerationsAndReleaseEachExactlyOnce() {
    NodeActivityTracker.Generation first = tracker.begin("n1");
    NodeActivityTracker.Generation second = tracker.begin("n1");
    assertEquals(2, tracker.active("n1"));

    first.close();
    first.close();

    assertEquals(1, tracker.active("n1"));
    second.close();
    assertEquals(0, tracker.active("n1"));
  }
}
