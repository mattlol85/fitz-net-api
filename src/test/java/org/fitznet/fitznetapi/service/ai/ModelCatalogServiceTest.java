package org.fitznet.fitznetapi.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.fitznet.fitznetapi.dto.responses.ModelCatalogDto;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.service.OllamaModelInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ModelCatalogServiceTest {

  private static final String EXPIRES = "2026-11-12T13:33:00.000-05:00";

  private final ObjectMapper mapper = new ObjectMapper();
  private final NodeActivityTracker tracker = new NodeActivityTracker();
  private final WarmUpRegistry registry = new WarmUpRegistry();
  private final AiNodeRepository repository = mock(AiNodeRepository.class);
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  private FakeOllama ollama;

  @BeforeEach
  void startOllama() throws Exception {
    ollama = new FakeOllama();
  }

  @AfterEach
  void stopOllama() {
    ollama.close();
    executor.shutdownNow();
  }

  private ModelCatalogService service(Duration forceRefreshInterval) {
    return new ModelCatalogService(
        repository,
        new NodeStatusResolver(tracker, Duration.ofMinutes(5)),
        tracker,
        registry,
        new OllamaModelInfo(mapper, Duration.ofSeconds(2), 8192, Duration.ofMinutes(10)),
        mapper,
        executor,
        Duration.ofSeconds(60),
        Duration.ofSeconds(5),
        Duration.ofSeconds(2),
        Duration.ofSeconds(4),
        forceRefreshInterval);
  }

  private ModelCatalogService service() {
    return service(Duration.ZERO);
  }

  private AiNode.AiNodeBuilder node(String id, String name, String address) {
    return AiNode.builder()
        .id(id)
        .name(name)
        .status(NodeStatus.ONLINE)
        .os("Windows 11")
        .models(List.of("llama3.1:8b"))
        .vramGb(16.0)
        .address(address)
        .lastHeartbeatAt(Instant.now());
  }

  private void nodes(AiNode... nodes) {
    when(repository.findAll()).thenReturn(List.of(nodes));
    for (AiNode node : nodes) {
      when(repository.findById(node.getId())).thenReturn(Optional.of(node));
    }
  }

  private FakeOllama withLlama() {
    return ollama
        .tags(FakeOllama.tag("llama3.1:8b", "sha-1", 4_920_753_328L, "llama", "8.0B", "Q4_K_M"))
        .ps(FakeOllama.loaded("llama3.1:8b", 5_300L * 1024 * 1024, EXPIRES))
        .show("llama3.1:8b", 131_072, "completion", "vision");
  }

  private JsonNode json(ModelCatalogService service, boolean refresh) throws Exception {
    return mapper.readTree(mapper.writeValueAsString(service.toDto(service.catalog(refresh))));
  }

  @Test
  void shouldBuildTheSectionThreeJsonFromTagsPsAndShow() throws Exception {
    withLlama();
    nodes(node("n1", "Matt-PC", ollama.address()).build());

    JsonNode body = json(service(), false);

    JsonNode node = body.path("nodes").get(0);
    assertEquals("n1", node.path("id").asText());
    assertEquals("Matt-PC", node.path("name").asText());
    assertEquals("ONLINE", node.path("status").asText());
    assertEquals("Windows 11", node.path("os").asText());
    assertEquals(0, node.path("activeGenerations").asInt());
    assertFalse(node.path("catalogStale").asBoolean());
    assertEquals(16384, node.path("vram").path("totalMb").asInt());
    assertEquals(5300, node.path("vram").path("usedMb").asInt());
    assertEquals("ollama", node.path("vram").path("source").asText());

    JsonNode model = node.path("models").get(0);
    assertEquals("llama3.1:8b", model.path("name").asText());
    assertEquals("llama", model.path("family").asText());
    assertEquals("8.0B", model.path("parameterSize").asText());
    assertEquals("Q4_K_M", model.path("quantization").asText());
    assertEquals(4_920_753_328L, model.path("sizeBytes").asLong());
    assertEquals(8192, model.path("contextLength").asInt());
    assertEquals(131_072, model.path("maxContextLength").asInt());
    assertEquals("completion", model.path("capabilities").get(0).asText());
    assertEquals("vision", model.path("capabilities").get(1).asText());
    assertTrue(model.path("loaded").asBoolean());
    assertEquals("2026-11-12T18:33:00Z", model.path("loadedUntil").asText());
    assertEquals(5300, model.path("vramMb").asInt());

    JsonNode auto = body.path("models").get(0);
    assertEquals("llama3.1:8b", auto.path("name").asText());
    assertEquals("n1", auto.path("nodeIds").get(0).asText());
    assertEquals("n1", auto.path("loadedOn").get(0).asText());
    assertEquals("n1", auto.path("autoNodeId").asText());
    assertEquals("8.0B", auto.path("parameterSize").asText());
    assertEquals(8192, auto.path("contextLength").asInt());
    assertTrue(body.path("generatedAt").asText().endsWith("Z"));
  }

  @Test
  void shouldNeverExposeTheNodeAddress() throws Exception {
    withLlama();
    nodes(node("n1", "Matt-PC", ollama.address()).build());

    String raw = mapper.writeValueAsString(service().toDto(service().catalog(false)));

    assertFalse(raw.contains("127.0.0.1"), raw);
    assertFalse(raw.toLowerCase().contains("address"), raw);
  }

  @Test
  void shouldPreferFreshHeartbeatVramOverOllamaFigures() throws Exception {
    withLlama();
    nodes(
        node("n1", "Matt-PC", ollama.address())
            .vramTotalMb(24576)
            .vramUsedMb(1000)
            .vramReportedAt(Instant.now())
            .build());

    JsonNode vram = json(service(), false).path("nodes").get(0).path("vram");

    assertEquals(24576, vram.path("totalMb").asInt());
    assertEquals(1000, vram.path("usedMb").asInt());
    assertEquals("heartbeat", vram.path("source").asText());
  }

  @Test
  void shouldIgnoreHeartbeatVramThatIsOlderThanTheStaleWindow() throws Exception {
    withLlama();
    nodes(
        node("n1", "Matt-PC", ollama.address())
            .vramTotalMb(24576)
            .vramUsedMb(1000)
            .vramReportedAt(Instant.now().minus(Duration.ofHours(1)))
            .build());

    JsonNode vram = json(service(), false).path("nodes").get(0).path("vram");

    assertEquals(16384, vram.path("totalMb").asInt());
    assertEquals("ollama", vram.path("source").asText());
  }

  @Test
  void shouldLeaveEmbeddingModelsOut() throws Exception {
    ollama
        .tags(
            FakeOllama.tag("llama3.1:8b", "sha-1", 1L, "llama", "8.0B", "Q4_K_M"),
            FakeOllama.tag("nomic-embed-text", "sha-2", 1L, "nomic-bert", "137M", "F16"))
        .show("llama3.1:8b", 8192, "completion")
        .show("nomic-embed-text", 2048, "embedding");
    nodes(node("n1", "Matt-PC", ollama.address()).build());

    JsonNode body = json(service(), false);

    JsonNode models = body.path("nodes").get(0).path("models");
    assertEquals(1, models.size());
    assertEquals("llama3.1:8b", models.get(0).path("name").asText());
    assertEquals(1, body.path("models").size());
  }

  @Test
  void shouldKeepModelsWhoseCapabilitiesAreUnknown() throws Exception {
    ollama
        .tags(FakeOllama.tag("old-model:1b", "sha-1", 1L, "llama", "1B", "Q4_0"))
        .show("old-model:1b", 4096);
    nodes(node("n1", "Matt-PC", ollama.address()).build());

    JsonNode model = json(service(), false).path("nodes").get(0).path("models").get(0);

    assertEquals("old-model:1b", model.path("name").asText());
    assertEquals(0, model.path("capabilities").size());
    assertEquals(4096, model.path("contextLength").asInt());
  }

  @Test
  void shouldServeTheLastSnapshotAsStaleWhenOllamaGoesDown() throws Exception {
    withLlama();
    nodes(node("n1", "Matt-PC", ollama.address()).build());
    ModelCatalogService service = service();
    assertFalse(json(service, false).path("nodes").get(0).path("catalogStale").asBoolean());

    ollama.close();
    JsonNode node = json(service, true).path("nodes").get(0);

    assertTrue(node.path("catalogStale").asBoolean());
    JsonNode model = node.path("models").get(0);
    assertEquals("llama3.1:8b", model.path("name").asText());
    assertEquals("llama", model.path("family").asText());
    assertEquals(131_072, model.path("maxContextLength").asInt());
    assertEquals("completion", model.path("capabilities").get(0).asText());
  }

  @Test
  void shouldFallBackToHeartbeatNamesWithNullDetailsWhenThereIsNoSnapshot() throws Exception {
    nodes(node("n1", "Matt-PC", ollama.address()).models(List.of("b:1", "a:1")).build());
    ollama.close();

    JsonNode node = json(service(), false).path("nodes").get(0);

    assertTrue(node.path("catalogStale").asBoolean());
    assertEquals(2, node.path("models").size());
    JsonNode first = node.path("models").get(0);
    assertEquals("a:1", first.path("name").asText());
    assertTrue(first.path("family").isNull());
    assertTrue(first.path("parameterSize").isNull());
    assertTrue(first.path("sizeBytes").isNull());
    assertTrue(first.path("contextLength").isNull());
    assertTrue(first.path("maxContextLength").isNull());
    assertEquals(0, first.path("capabilities").size());
    assertFalse(first.path("loaded").asBoolean());
  }

  @Test
  void shouldNotContactAnOfflineNodeAndListItFromHeartbeatNames() throws Exception {
    withLlama();
    nodes(
        node("n1", "Matt-PC", ollama.address())
            .lastHeartbeatAt(Instant.now().minus(Duration.ofMinutes(30)))
            .build());

    JsonNode body = json(service(), false);

    JsonNode node = body.path("nodes").get(0);
    assertEquals("OFFLINE", node.path("status").asText());
    assertTrue(node.path("catalogStale").asBoolean());
    assertEquals("llama3.1:8b", node.path("models").get(0).path("name").asText());
    assertEquals(0, body.path("models").size(), "OFFLINE nodes are not Auto candidates");
    assertEquals(0, ollama.tagsCalls.get());
    assertEquals(0, ollama.psCalls.get());
    assertEquals(0, ollama.showCalls.get());
  }

  @Test
  void shouldCacheWithinTheTtlAndRefetchOnRefresh() throws Exception {
    withLlama();
    nodes(node("n1", "Matt-PC", ollama.address()).build());
    ModelCatalogService service = service();

    service.catalog(false);
    service.catalog(false);
    assertEquals(1, ollama.tagsCalls.get());
    assertEquals(1, ollama.psCalls.get());

    service.catalog(true);
    assertEquals(2, ollama.tagsCalls.get());
    assertEquals(2, ollama.psCalls.get());
    assertEquals(1, ollama.showCalls.get(), "show is cached for the model-info TTL");
  }

  @Test
  void shouldRateLimitForcedRefreshesPerNode() throws Exception {
    withLlama();
    nodes(node("n1", "Matt-PC", ollama.address()).build());
    ModelCatalogService service = service(Duration.ofHours(1));

    service.catalog(true);
    service.catalog(true);
    service.catalog(true);

    assertEquals(1, ollama.tagsCalls.get());
  }

  @Test
  void shouldReportActiveGenerationsAndBusyStatus() throws Exception {
    withLlama();
    nodes(node("n1", "Matt-PC", ollama.address()).build());

    try (NodeActivityTracker.Generation ignored = tracker.begin("n1")) {
      JsonNode node = json(service(), false).path("nodes").get(0);
      assertEquals("BUSY", node.path("status").asText());
      assertEquals(1, node.path("activeGenerations").asInt());
    }
  }

  @Test
  void shouldCountAnInFlightWarmUpAsLoaded() throws Exception {
    ollama
        .tags(FakeOllama.tag("llama3.1:8b", "sha-1", 1L, "llama", "8.0B", "Q4_K_M"))
        .show("llama3.1:8b", 8192, "completion");
    nodes(node("n1", "Matt-PC", ollama.address()).build());
    ModelCatalogService service = service();
    assertFalse(json(service, false).path("nodes").get(0).path("models").get(0).path("loaded").asBoolean());

    registry.tryBegin("n1", "llama3.1:8b");
    JsonNode body = json(service, false);

    assertTrue(body.path("nodes").get(0).path("models").get(0).path("loaded").asBoolean());
    assertEquals("n1", body.path("models").get(0).path("loadedOn").get(0).asText());
    assertTrue(body.path("nodes").get(0).path("models").get(0).path("vramMb").isNull());
  }

  @Test
  void shouldPointAutoAtTheWarmNodeAndSortNodesByName() throws Exception {
    withLlama();
    try (FakeOllama second = new FakeOllama()) {
      second
          .tags(FakeOllama.tag("llama3.1:8b", "sha-1", 4_920_753_328L, "llama", "8.0B", "Q4_K_M"))
          .show("llama3.1:8b", 8192, "completion");
      nodes(
          node("n2", "Zed-PC", ollama.address()).build(),
          node("n1", "Alpha-PC", second.address()).build());
      // Zed-PC has the model loaded, Alpha-PC does not: warm beats alphabetical order.

      ModelCatalogDto dto = service().toDto(service().catalog(false));

      assertEquals("Alpha-PC", dto.nodes().get(0).name());
      assertEquals("Zed-PC", dto.nodes().get(1).name());
      ModelCatalogDto.AutoModelEntry auto = dto.models().get(0);
      assertEquals("n2", auto.autoNodeId());
      assertEquals(List.of("n2"), auto.loadedOn());
      assertEquals(2, auto.nodeIds().size());
    }
  }

  @Test
  void shouldFindASingleNodeAndReportAnUnknownOneAsEmpty() throws Exception {
    withLlama();
    nodes(node("n1", "Matt-PC", ollama.address()).build());
    when(repository.findById("nope")).thenReturn(Optional.empty());

    assertTrue(service().node("n1", false).isPresent());
    assertTrue(service().node("nope", false).isEmpty());
  }
}
