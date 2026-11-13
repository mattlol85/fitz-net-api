package org.fitznet.fitznetapi.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.fitznet.fitznetapi.service.OllamaModelInfo;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ModelRouterTest {

  private static final String MODEL = "llama3.1:8b";
  private static final long GIB = 1024L * 1024L * 1024L;

  private final ModelCatalogService catalogService = mock(ModelCatalogService.class);
  private final ModelRouter router =
      new ModelRouter(
          catalogService,
          new OllamaModelInfo(new ObjectMapper(), Duration.ofSeconds(2), 8192, Duration.ofMinutes(10)));

  // ---- fixtures ----------------------------------------------------------------------------

  /** A routable node with the model; tweak through the builder. */
  private static final class NodeSpec {
    String id;
    String name;
    String address = "127.0.0.1:11434";
    NodeStatus status = NodeStatus.ONLINE;
    NodeStatus storedStatus = NodeStatus.ONLINE;
    int active;
    Integer totalMb = 16384;
    Integer usedMb = 0;
    boolean hasModel = true;
    boolean loaded;
    boolean warming;
    Long sizeBytes = 5 * GIB;
    Integer contextLength = 8192;
    List<String> capabilities = List.of("completion");

    NodeSpec(String id, String name) {
      this.id = id;
      this.name = name;
    }

    NodeSpec active(int n) {
      this.active = n;
      return this;
    }

    NodeSpec loaded() {
      this.loaded = true;
      return this;
    }

    NodeSpec warming() {
      this.warming = true;
      return this;
    }

    NodeSpec stored(NodeStatus s) {
      this.storedStatus = s;
      if (s == NodeStatus.OFFLINE) {
        this.status = NodeStatus.OFFLINE;
      } else if (this.status != NodeStatus.OFFLINE) {
        this.status = s;
      }
      return this;
    }

    NodeSpec offline() {
      this.status = NodeStatus.OFFLINE;
      return this;
    }

    NodeSpec noAddress() {
      this.address = null;
      return this;
    }

    NodeSpec vram(Integer total, Integer used) {
      this.totalMb = total;
      this.usedMb = used;
      return this;
    }

    NodeSpec without() {
      this.hasModel = false;
      return this;
    }

    NodeSpec size(Long bytes) {
      this.sizeBytes = bytes;
      return this;
    }

    CatalogNode build() {
      List<CatalogModel> models =
          hasModel
              ? List.of(
                  new CatalogModel(
                      MODEL,
                      "llama",
                      "8.0B",
                      "Q4_K_M",
                      sizeBytes,
                      contextLength,
                      131072,
                      capabilities,
                      loaded,
                      warming,
                      null,
                      null))
              : List.of();
      return new CatalogNode(
          id,
          name,
          address,
          "Windows 11",
          status,
          storedStatus,
          Instant.now(),
          active,
          false,
          totalMb,
          usedMb,
          totalMb == null ? null : "heartbeat",
          models);
    }
  }

  private static NodeSpec node(String id, String name) {
    return new NodeSpec(id, name);
  }

  private void catalog(NodeSpec... specs) {
    List<CatalogNode> nodes = new ArrayList<>();
    for (NodeSpec spec : specs) {
      nodes.add(spec.build());
    }
    Catalog catalog = new Catalog(Instant.now(), nodes);
    when(catalogService.catalog(false)).thenReturn(catalog);
    for (CatalogNode node : nodes) {
      when(catalogService.node(node.id(), false)).thenReturn(Optional.of(node));
    }
  }

  private RoutedTarget routed(RouteOutcome outcome) {
    return assertInstanceOf(RouteOutcome.Routed.class, outcome).target();
  }

  private RouteOutcome.Failed failed(RouteOutcome outcome) {
    return assertInstanceOf(RouteOutcome.Failed.class, outcome);
  }

  private String autoWinner() {
    return routed(router.route(MODEL, null, Set.of())).nodeId();
  }

  // ---- Auto ordering -----------------------------------------------------------------------

  @Test
  void shouldPreferAWarmNodeOverAnIdlerColdOne() {
    catalog(node("a", "Alpha").active(0), node("b", "Bravo").active(2).loaded());

    assertEquals("b", autoWinner());
  }

  @Test
  void shouldTreatAnInFlightWarmUpAsWarm() {
    catalog(node("a", "Alpha"), node("b", "Bravo").warming());

    assertEquals("b", autoWinner());
  }

  @Test
  void shouldPreferTheLeastBusyNodeAmongEquallyWarmOnes() {
    catalog(
        node("a", "Alpha").loaded().active(2),
        node("b", "Bravo").loaded().active(1),
        node("c", "Charlie").loaded().active(3));

    assertEquals("b", autoWinner());
  }

  @Test
  void shouldPreferHeartbeatOnlineOverBusyWhenGenerationsTie() {
    catalog(node("a", "Alpha").stored(NodeStatus.BUSY), node("b", "Bravo").stored(NodeStatus.ONLINE));

    assertEquals("b", autoWinner());
  }

  @Test
  void shouldPreferFewerGenerationsEvenOverHeartbeatStatus() {
    catalog(
        node("a", "Alpha").stored(NodeStatus.BUSY).active(0),
        node("b", "Bravo").stored(NodeStatus.ONLINE).active(1));

    assertEquals("a", autoWinner());
  }

  @Test
  void shouldPreferANodeWhereTheModelFitsOverOneWhereItDoesNot() {
    // Alpha reports 1 GiB free (a 5 GiB model does not fit); Bravo has no VRAM figures at all, so
    // it counts as fitting even though it sorts last on free VRAM.
    catalog(node("a", "Alpha").vram(16384, 15360), node("b", "Bravo").vram(null, null));

    assertEquals("b", autoWinner());
  }

  @Test
  void shouldPreferMoreFreeVramAmongNodesWhereItFits() {
    catalog(
        node("a", "Alpha").vram(16384, 8192),
        node("b", "Bravo").vram(24576, 2048),
        node("c", "Charlie").vram(16384, 4096));

    assertEquals("b", autoWinner());
  }

  @Test
  void shouldSortUnknownFreeVramAfterKnownOnes() {
    catalog(node("a", "Alpha").vram(null, null), node("b", "Bravo").vram(16384, 8192));

    assertEquals("b", autoWinner());
  }

  @Test
  void shouldBreakTiesByNameThenId() {
    catalog(node("z", "Same"), node("y", "Same"), node("a", "Zulu"));

    assertEquals("y", autoWinner());
  }

  @Test
  void shouldRankByEachCriterionInOrderWhenAllDiffer() {
    catalog(node("3", "Lightly").active(1).vram(16384, 0), node("2", "Idle").active(0).vram(16384, 0),
        node("1", "Warm").loaded().active(5).vram(1024, 1024));

    List<String> order = router.alternatives(MODEL, Set.of()).stream().map(Alternative::nodeId).toList();

    assertEquals(List.of("1", "2", "3"), order);
  }

  @Test
  void shouldSkipOfflineNodesNodesWithoutAddressAndNodesWithoutTheModel() {
    catalog(
        node("a", "Alpha").offline(),
        node("b", "Bravo").noAddress(),
        node("c", "Charlie").without(),
        node("d", "Delta"));

    assertEquals("d", autoWinner());
  }

  @Test
  void shouldHonourTheExcludedNodeSetForAuto() {
    catalog(node("a", "Alpha").loaded(), node("b", "Bravo"));

    RoutedTarget target = routed(router.route(MODEL, null, Set.of("a")));

    assertEquals("b", target.nodeId());
    assertTrue(target.auto());
  }

  @Test
  void shouldFailWithNoNodeAvailableWhenNothingQualifies() {
    catalog(node("a", "Alpha").offline(), node("b", "Bravo").without());

    RouteOutcome.Failed failure = failed(router.route(MODEL, null, Set.of()));

    assertEquals(ChatErrorCodes.NO_NODE_AVAILABLE, failure.code());
    assertTrue(failure.alternatives().isEmpty());
    assertTrue(failure.retryable());
  }

  @Test
  void shouldFailWithNoNodeAvailableWhenEveryCandidateWasAlreadyTried() {
    catalog(node("a", "Alpha"));

    assertEquals(
        ChatErrorCodes.NO_NODE_AVAILABLE, failed(router.route(MODEL, null, Set.of("a"))).code());
  }

  @Test
  void shouldFillTheRoutedTargetFromTheCatalogueEntry() {
    NodeSpec spec = node("a", "Alpha").loaded();
    spec.capabilities = List.of("completion", "vision", "thinking");
    spec.contextLength = 4096;
    catalog(spec);

    RoutedTarget target = routed(router.route(MODEL, null, Set.of()));

    assertEquals("a", target.nodeId());
    assertEquals("Alpha", target.nodeName());
    assertEquals("127.0.0.1:11434", target.address());
    assertEquals(MODEL, target.model());
    assertTrue(target.auto());
    assertTrue(target.loaded());
    assertEquals(4096, target.contextLength());
    assertTrue(target.hasCapability("vision"));
    assertTrue(target.hasCapability("thinking"));
    assertFalse(target.hasCapability("tools"));
  }

  @Test
  void shouldFallBackToTheConfiguredCapWhenTheNativeWindowIsUnknown() {
    NodeSpec spec = node("a", "Alpha");
    spec.contextLength = null;
    catalog(spec);

    assertEquals(8192, routed(router.route(MODEL, null, Set.of())).contextLength());
  }

  @Test
  void shouldCapTheContextAtAPresetNumCtx() {
    catalog(node("a", "Alpha"));

    RoutedTarget target = routed(router.route(MODEL, null, Set.of()));

    assertEquals(2048, target.withContextCap(2048).contextLength());
    assertEquals(8192, target.withContextCap(16384).contextLength());
    assertEquals(8192, target.withContextCap(null).contextLength());
  }

  // ---- explicit node -----------------------------------------------------------------------

  @Test
  void shouldRouteToTheExplicitNodeEvenWhenAnotherIsWarm() {
    catalog(node("a", "Alpha"), node("b", "Bravo").loaded());

    RoutedTarget target = routed(router.route(MODEL, "a", Set.of()));

    assertEquals("a", target.nodeId());
    assertFalse(target.auto());
  }

  @Test
  void shouldIgnoreTheExcludedSetForAnExplicitNode() {
    catalog(node("a", "Alpha"));

    assertEquals("a", routed(router.route(MODEL, "a", Set.of("a"))).nodeId());
  }

  @Test
  void shouldAnswerNodeOfflineForAnOfflineExplicitNodeWithAlternativesInAutoOrder() {
    catalog(
        node("a", "Alpha").offline(),
        node("b", "Bravo"),
        node("c", "Charlie").loaded().active(1),
        node("d", "Delta").without());

    RouteOutcome.Failed failure = failed(router.route(MODEL, "a", Set.of()));

    assertEquals(ChatErrorCodes.NODE_OFFLINE, failure.code());
    assertTrue(failure.retryable());
    assertEquals(
        List.of("c", "b"), failure.alternatives().stream().map(Alternative::nodeId).toList());
    Alternative first = failure.alternatives().get(0);
    assertEquals("Charlie", first.nodeName());
    assertEquals(MODEL, first.model());
    assertTrue(first.loaded());
    assertEquals(NodeStatus.ONLINE, first.status());
  }

  @Test
  void shouldAnswerNodeOfflineForAnExplicitNodeWithoutAnAddress() {
    catalog(node("a", "Alpha").noAddress(), node("b", "Bravo"));

    RouteOutcome.Failed failure = failed(router.route(MODEL, "a", Set.of()));

    assertEquals(ChatErrorCodes.NODE_OFFLINE, failure.code());
    assertEquals(1, failure.alternatives().size());
    assertEquals("b", failure.alternatives().get(0).nodeId());
  }

  @Test
  void shouldAnswerNodeOfflineForAnUnknownExplicitNode() {
    catalog(node("b", "Bravo"));
    when(catalogService.node("ghost", false)).thenReturn(Optional.empty());

    RouteOutcome.Failed failure = failed(router.route(MODEL, "ghost", Set.of()));

    assertEquals(ChatErrorCodes.NODE_OFFLINE, failure.code());
    assertEquals("b", failure.alternatives().get(0).nodeId());
  }

  @Test
  void shouldAnswerModelNotOnNodeAndNeverSubstituteSilently() {
    catalog(node("a", "Alpha").without(), node("b", "Bravo"), node("c", "Charlie").offline());

    RouteOutcome.Failed failure = failed(router.route(MODEL, "a", Set.of()));

    assertEquals(ChatErrorCodes.MODEL_NOT_ON_NODE, failure.code());
    assertEquals(
        List.of("b"), failure.alternatives().stream().map(Alternative::nodeId).toList());
  }

  @Test
  void shouldNotListTheFailedNodeAmongItsOwnAlternatives() {
    catalog(node("a", "Alpha").offline(), node("b", "Bravo"));

    RouteOutcome.Failed failure = failed(router.route(MODEL, "a", Set.of()));

    assertFalse(failure.alternatives().stream().anyMatch(alt -> alt.nodeId().equals("a")));
  }

  @Test
  void shouldAnswerEmptyAlternativesWhenNobodyElseHasTheModel() {
    catalog(node("a", "Alpha").offline());

    RouteOutcome.Failed failure = failed(router.route(MODEL, "a", Set.of()));

    assertEquals(ChatErrorCodes.NODE_OFFLINE, failure.code());
    assertTrue(failure.alternatives().isEmpty());
  }

  @Test
  void shouldListAlternativesForAFailedNodeExcludingTriedOnes() {
    catalog(node("a", "Alpha"), node("b", "Bravo"), node("c", "Charlie"));

    List<Alternative> alternatives = router.alternatives(MODEL, Set.of("a", "b"));

    assertEquals(List.of("c"), alternatives.stream().map(Alternative::nodeId).toList());
  }

  @Test
  void shouldRejectABlankModelWithoutTouchingTheCatalogue() {
    RouteOutcome.Failed failure = failed(router.route(" ", null, Set.of()));

    assertEquals(ChatErrorCodes.NO_NODE_AVAILABLE, failure.code());
  }
}
