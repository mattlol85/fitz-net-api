package org.fitznet.fitznetapi.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.fitznet.fitznetapi.dto.responses.WarmUpResponseDto;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.service.OllamaModelInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WarmUpServiceTest {

  private static final String MODEL = "llama3.1:8b";

  private final ObjectMapper mapper = new ObjectMapper();
  private final NodeActivityTracker tracker = new NodeActivityTracker();
  private final WarmUpRegistry registry = new WarmUpRegistry();
  private final AiNodeRepository repository = mock(AiNodeRepository.class);
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  private FakeOllama ollama;
  private ModelCatalogService catalogService;
  private WarmUpService warmUp;

  @BeforeEach
  void setUp() throws Exception {
    ollama = new FakeOllama();
    ollama
        .tags(FakeOllama.tag(MODEL, "sha-1", 4_920_753_328L, "llama", "8.0B", "Q4_K_M"))
        .show(MODEL, 8192, "completion");
    OllamaModelInfo info =
        new OllamaModelInfo(mapper, Duration.ofSeconds(2), 8192, Duration.ofMinutes(10));
    catalogService =
        new ModelCatalogService(
            repository,
            new NodeStatusResolver(tracker, Duration.ofMinutes(5)),
            tracker,
            registry,
            info,
            mapper,
            executor,
            Duration.ofSeconds(60),
            Duration.ofSeconds(5),
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            Duration.ZERO);
    warmUp =
        new WarmUpService(
            new ModelRouter(catalogService, info),
            registry,
            catalogService,
            mapper,
            executor,
            Duration.ofSeconds(2),
            Duration.ofSeconds(10),
            "30m");
    register(node("n1", "Matt-PC", ollama.address()).build());
  }

  @AfterEach
  void tearDown() {
    ollama.close();
    executor.shutdownNow();
  }

  private static AiNode.AiNodeBuilder node(String id, String name, String address) {
    return AiNode.builder()
        .id(id)
        .name(name)
        .status(NodeStatus.ONLINE)
        .models(List.of(MODEL))
        .address(address)
        .lastHeartbeatAt(Instant.now());
  }

  private void register(AiNode... nodes) {
    when(repository.findAll()).thenReturn(List.of(nodes));
    for (AiNode node : nodes) {
      when(repository.findById(node.getId())).thenReturn(Optional.of(node));
    }
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met in time");
      }
      Thread.sleep(10);
    }
  }

  @Test
  void shouldReportLoadedWithoutPreloadingWhenTheModelIsInMemory() {
    ollama.ps(FakeOllama.loaded(MODEL, 5L * 1024 * 1024 * 1024, "2026-11-12T13:33:00-05:00"));

    WarmUpResponseDto response = warmUp.warmUp(MODEL, null);

    assertEquals("loaded", response.state());
    assertEquals("n1", response.nodeId());
    assertEquals("Matt-PC", response.nodeName());
    assertEquals(MODEL, response.model());
    assertNull(response.message());
    assertEquals(0, ollama.generateCalls.get());
  }

  @Test
  void shouldStartAPreloadWithKeepAliveAndReportLoading() throws Exception {
    ollama.holdGenerate();

    WarmUpResponseDto response = warmUp.warmUp(MODEL, null);

    assertEquals("loading", response.state());
    assertEquals("n1", response.nodeId());
    await(() -> ollama.generateCalls.get() == 1);
    String body = ollama.generateBodies().get(0);
    assertTrue(body.contains("\"model\":\"" + MODEL + "\""), body);
    assertTrue(body.contains("\"keep_alive\":\"30m\""), body);
    assertTrue(registry.isInFlight("n1", MODEL));
    ollama.releaseGenerate();
    await(() -> !registry.isInFlight("n1", MODEL));
  }

  @Test
  void shouldShareOneInFlightPreloadAcrossConcurrentWarmUps() throws Exception {
    ollama.holdGenerate();

    WarmUpResponseDto first = warmUp.warmUp(MODEL, null);
    await(() -> ollama.generateCalls.get() == 1);
    WarmUpResponseDto second = warmUp.warmUp(MODEL, "n1");
    WarmUpResponseDto third = warmUp.warmUp(MODEL, null);

    assertEquals("loading", first.state());
    assertEquals("loading", second.state());
    assertEquals("loading", third.state());
    Thread.sleep(150);
    assertEquals(1, ollama.generateCalls.get(), "only one /api/generate per node + model");
    ollama.releaseGenerate();
    await(() -> !registry.isInFlight("n1", MODEL));
  }

  @Test
  void shouldCountTheInFlightPreloadAsLoadedInTheCatalogue() throws Exception {
    ollama.holdGenerate();
    warmUp.warmUp(MODEL, null);
    await(() -> ollama.generateCalls.get() == 1);

    CatalogModel model = catalogService.catalog(false).nodes().get(0).model(MODEL).orElseThrow();

    assertTrue(model.loaded());
    assertTrue(model.warming());
    assertFalse(model.loadedInOllama());
    ollama.releaseGenerate();
    await(() -> !registry.isInFlight("n1", MODEL));
  }

  @Test
  void shouldReportLoadedOnceThePreloadFinishedAndOllamaListsTheModel() throws Exception {
    ollama.holdGenerate();
    warmUp.warmUp(MODEL, null);
    await(() -> ollama.generateCalls.get() == 1);
    ollama.ps(FakeOllama.loaded(MODEL, 5L * 1024 * 1024 * 1024, "2026-11-12T13:33:00-05:00"));
    ollama.releaseGenerate();
    await(() -> !registry.isInFlight("n1", MODEL));

    WarmUpResponseDto response = warmUp.warmUp(MODEL, null);

    assertEquals("loaded", response.state());
    assertEquals(1, ollama.generateCalls.get());
  }

  @Test
  void shouldAllowANewPreloadAfterAFailedOne() throws Exception {
    ollama.generateStatus(500);
    warmUp.warmUp(MODEL, null);
    await(() -> ollama.generateCalls.get() == 1 && !registry.isInFlight("n1", MODEL));

    WarmUpResponseDto again = warmUp.warmUp(MODEL, null);

    assertEquals("loading", again.state());
    await(() -> ollama.generateCalls.get() == 2);
    await(() -> !registry.isInFlight("n1", MODEL));
  }

  @Test
  void shouldBeUnavailableWhenNoNodeHasTheModel() {
    WarmUpResponseDto response = warmUp.warmUp("qwen3:14b", null);

    assertEquals("unavailable", response.state());
    assertNull(response.nodeId());
    assertEquals("qwen3:14b", response.model());
    assertNotNull(response.message());
    assertEquals(0, ollama.generateCalls.get());
  }

  @Test
  void shouldBeUnavailableForAnOfflineExplicitNodeAndSayWhy() {
    register(
        node("n1", "Matt-PC", ollama.address())
            .lastHeartbeatAt(Instant.now().minus(Duration.ofHours(1)))
            .build());

    WarmUpResponseDto response = warmUp.warmUp(MODEL, "n1");

    assertEquals("unavailable", response.state());
    assertEquals("n1", response.nodeId());
    assertTrue(response.message().contains("offline"), response.message());
    assertEquals(0, ollama.generateCalls.get());
  }

  @Test
  void shouldBeUnavailableForAnExplicitNodeThatLacksTheModel() {
    register(node("n1", "Matt-PC", ollama.address()).models(List.of("other:1b")).build());
    ollama.tags(FakeOllama.tag("other:1b", "sha-9", 1L, "llama", "1B", "Q4_0"));

    WarmUpResponseDto response = warmUp.warmUp(MODEL, "n1");

    assertEquals("unavailable", response.state());
    assertEquals("n1", response.nodeId());
  }
}
