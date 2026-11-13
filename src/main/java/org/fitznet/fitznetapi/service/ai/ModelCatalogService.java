package org.fitznet.fitznetapi.service.ai;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import org.fitznet.fitznetapi.config.AiExecutorConfig;
import org.fitznet.fitznetapi.dto.responses.ModelCatalogDto;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.service.OllamaHttp;
import org.fitznet.fitznetapi.service.OllamaModelInfo;
import org.fitznet.fitznetapi.service.OllamaModelInfo.ModelDetails;
import org.fitznet.fitznetapi.tracing.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The picker catalogue: which models each node has, which are loaded, and what they can do, read
 * straight from each node's Ollama (contract sections 2.1 and 3) and cached briefly.
 *
 * <ul>
 *   <li>{@code /api/tags} (installed models) is cached for {@code ai.catalog.models-ttl}
 *   <li>{@code /api/ps} (loaded models) for {@code ai.catalog.loaded-ttl}
 *   <li>{@code /api/show} (native context, capabilities) by {@link OllamaModelInfo}
 * </ul>
 *
 * Fetches are lazy and run in parallel across nodes with short timeouts. A node whose effective
 * status is OFFLINE is never contacted. When a node's Ollama cannot be reached the last good
 * snapshot is served with {@code stale = true}; with no snapshot the node's heartbeat model names
 * are used with every detail field null. Models whose capabilities are known and lack
 * {@code completion} (embedding models) are left out. Nothing here logs model content.
 */
@Service
public class ModelCatalogService {

  private static final Logger logger = LoggerFactory.getLogger(ModelCatalogService.class);

  private record TagEntry(
      String name,
      String digest,
      Long sizeBytes,
      String family,
      String parameterSize,
      String quantization) {}

  private record TagsSnapshot(List<TagEntry> models, long fetchedAtNanos) {}

  private record LoadedEntry(String name, Long vramMb, Instant expiresAt) {}

  private record LoadedSnapshot(Map<String, LoadedEntry> byName, long fetchedAtNanos) {}

  /** Per-node cache; {@code lock} serialises refreshes so a burst of callers fetches once. */
  private static final class NodeState {
    final ReentrantLock lock = new ReentrantLock();
    volatile TagsSnapshot tags;
    volatile LoadedSnapshot loaded;
    boolean forcedBefore;
    long lastForcedNanos;
    boolean failedBefore;
    long lastFailureNanos;
  }

  private final AiNodeRepository aiNodeRepository;
  private final NodeStatusResolver statusResolver;
  private final NodeActivityTracker activityTracker;
  private final WarmUpRegistry warmUpRegistry;
  private final OllamaModelInfo modelInfo;
  private final ObjectMapper objectMapper;
  private final ExecutorService executor;
  private final Duration modelsTtl;
  private final Duration loadedTtl;
  private final Duration connectTimeout;
  private final Duration readTimeout;
  private final Duration forceRefreshInterval;
  private final Map<String, NodeState> states = new ConcurrentHashMap<>();

  public ModelCatalogService(
      AiNodeRepository aiNodeRepository,
      NodeStatusResolver statusResolver,
      NodeActivityTracker activityTracker,
      WarmUpRegistry warmUpRegistry,
      OllamaModelInfo modelInfo,
      ObjectMapper objectMapper,
      @Qualifier(AiExecutorConfig.AI_BACKGROUND_EXECUTOR) ExecutorService executor,
      @Value("${ai.catalog.models-ttl:60s}") Duration modelsTtl,
      @Value("${ai.catalog.loaded-ttl:5s}") Duration loadedTtl,
      @Value("${ai.catalog.connect-timeout:2s}") Duration connectTimeout,
      @Value("${ai.catalog.read-timeout:4s}") Duration readTimeout,
      @Value("${ai.catalog.force-refresh-interval:2s}") Duration forceRefreshInterval) {
    this.aiNodeRepository = aiNodeRepository;
    this.statusResolver = statusResolver;
    this.activityTracker = activityTracker;
    this.warmUpRegistry = warmUpRegistry;
    this.modelInfo = modelInfo;
    this.objectMapper = objectMapper;
    this.executor = executor;
    this.modelsTtl = modelsTtl;
    this.loadedTtl = loadedTtl;
    this.connectTimeout = connectTimeout;
    this.readTimeout = readTimeout;
    this.forceRefreshInterval = forceRefreshInterval;
  }

  /** Every registered node, refreshed in parallel where its cache is due. */
  public Catalog catalog(boolean refresh) {
    List<AiNode> nodes = aiNodeRepository.findAll();
    Instant now = Instant.now();
    List<CompletableFuture<CatalogNode>> futures =
        nodes.stream()
            .map(
                node ->
                    CompletableFuture.supplyAsync(
                        MdcPropagation.wrapSupplier(() -> buildNodeSafely(node, refresh, now)),
                        executor))
            .toList();
    List<CatalogNode> built =
        futures.stream()
            .map(CompletableFuture::join)
            .sorted(
                Comparator.comparing(
                        CatalogNode::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                    .thenComparing(CatalogNode::id))
            .toList();
    return new Catalog(now.truncatedTo(ChronoUnit.SECONDS), built);
  }

  /** One node only (explicit-node routing), or empty when it is not registered. */
  public Optional<CatalogNode> node(String nodeId, boolean refresh) {
    return aiNodeRepository
        .findById(nodeId)
        .map(node -> buildNodeSafely(node, refresh, Instant.now()));
  }

  /** Forget the cached {@code /api/ps} of a node, e.g. after a warm-up finished. */
  public void invalidateLoaded(String nodeId) {
    NodeState state = states.get(nodeId);
    if (state != null) {
      state.loaded = null;
    }
  }

  /** The client-facing JSON of section 3; never carries a node address. */
  public ModelCatalogDto toDto(Catalog catalog) {
    List<ModelCatalogDto.NodeEntry> nodes =
        catalog.nodes().stream()
            .map(
                n ->
                    new ModelCatalogDto.NodeEntry(
                        n.id(),
                        n.name(),
                        n.status().name(),
                        n.os(),
                        n.lastHeartbeatAt(),
                        n.activeGenerations(),
                        n.stale(),
                        new ModelCatalogDto.Vram(n.vramTotalMb(), n.vramUsedMb(), n.vramSource()),
                        n.models().stream()
                            .map(
                                m ->
                                    new ModelCatalogDto.ModelEntry(
                                        m.name(),
                                        m.family(),
                                        m.parameterSize(),
                                        m.quantization(),
                                        m.sizeBytes(),
                                        m.contextLength(),
                                        m.maxContextLength(),
                                        m.capabilities(),
                                        m.loaded(),
                                        m.loadedUntil(),
                                        m.vramMb()))
                            .toList()))
            .toList();
    return new ModelCatalogDto(catalog.generatedAt(), nodes, autoModels(catalog));
  }

  private List<ModelCatalogDto.AutoModelEntry> autoModels(Catalog catalog) {
    Map<String, List<CatalogNode>> byModel = new LinkedHashMap<>();
    for (CatalogNode node : catalog.nodes()) {
      if (node.status() == NodeStatus.OFFLINE) {
        continue;
      }
      for (CatalogModel model : node.models()) {
        byModel.computeIfAbsent(model.name(), k -> new ArrayList<>()).add(node);
      }
    }
    return byModel.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry -> {
              String name = entry.getKey();
              List<CatalogNode> ordered = new ArrayList<>(entry.getValue());
              ordered.sort(AutoOrder.comparator(name));
              List<CatalogNode> auto = AutoOrder.candidates(catalog, name, Set.of());
              CatalogNode chosen = auto.isEmpty() ? null : auto.get(0);
              CatalogNode detailNode = chosen != null ? chosen : ordered.get(0);
              CatalogModel detail = detailNode.model(name).orElseThrow();
              return new ModelCatalogDto.AutoModelEntry(
                  name,
                  ordered.stream().map(CatalogNode::id).toList(),
                  ordered.stream()
                      .filter(n -> n.model(name).map(CatalogModel::loaded).orElse(false))
                      .map(CatalogNode::id)
                      .toList(),
                  chosen == null ? null : chosen.id(),
                  detail.parameterSize(),
                  detail.quantization(),
                  detail.contextLength(),
                  detail.capabilities());
            })
        .toList();
  }

  // ---------------------------------------------------------------------------------------------

  private CatalogNode buildNodeSafely(AiNode node, boolean refresh, Instant now) {
    try {
      return buildNode(node, refresh, now);
    } catch (RuntimeException ex) {
      logger.warn("Could not build catalogue entry for node {}: {}", node.getId(), ex.toString());
      return new CatalogNode(
          node.getId(),
          node.getName(),
          node.getAddress(),
          node.getOs(),
          statusResolver.effective(node, now),
          node.getStatus(),
          node.getLastHeartbeatAt(),
          activityTracker.active(node.getId()),
          true,
          null,
          null,
          null,
          buildModels(node, null, null, false));
    }
  }

  private CatalogNode buildNode(AiNode node, boolean refresh, Instant now) {
    NodeStatus effective = statusResolver.effective(node, now);
    NodeState state = states.computeIfAbsent(node.getId(), id -> new NodeState());
    boolean live =
        effective != NodeStatus.OFFLINE && node.getAddress() != null && !node.getAddress().isBlank();
    boolean stale = !live;
    boolean fetchDetails = live;
    TagsSnapshot tags;
    LoadedSnapshot loaded;
    if (live) {
      state.lock.lock();
      try {
        stale = refreshSnapshots(node, state, refresh);
        tags = state.tags;
        loaded = state.loaded;
      } catch (RuntimeException ex) {
        logger.warn("Catalogue refresh failed for node {}: {}", node.getId(), ex.toString());
        stale = true;
        fetchDetails = false;
        tags = state.tags;
        loaded = state.loaded;
      } finally {
        state.lock.unlock();
      }
    } else {
      tags = state.tags;
      loaded = state.loaded;
    }

    List<CatalogModel> models = buildModels(node, tags, loaded, fetchDetails && !stale);
    Integer totalMb = null;
    Integer usedMb = null;
    String source = null;
    boolean reported =
        node.getVramReportedAt() != null
            && !node.getVramReportedAt().isBefore(now.minus(statusResolver.staleAfter()));
    if (reported && node.getVramTotalMb() != null) {
      totalMb = node.getVramTotalMb();
    } else if (node.getVramGb() != null) {
      totalMb = (int) Math.round(node.getVramGb() * 1024);
    }
    if (reported && node.getVramUsedMb() != null) {
      usedMb = node.getVramUsedMb();
      source = "heartbeat";
    } else if (loaded != null) {
      usedMb =
          (int)
              loaded.byName().values().stream()
                  .map(LoadedEntry::vramMb)
                  .filter(v -> v != null)
                  .mapToLong(Long::longValue)
                  .sum();
      source = "ollama";
    }

    return new CatalogNode(
        node.getId(),
        node.getName(),
        node.getAddress(),
        node.getOs(),
        effective,
        node.getStatus(),
        node.getLastHeartbeatAt(),
        activityTracker.active(node.getId()),
        stale,
        totalMb,
        usedMb,
        source,
        models);
  }

  /**
   * Brings the node's cached {@code /api/tags} and {@code /api/ps} up to date where they are due.
   * Returns true when Ollama could not be reached (the cached data, if any, is served as stale).
   */
  private boolean refreshSnapshots(AiNode node, NodeState state, boolean refresh) {
    long nowNanos = System.nanoTime();
    boolean force =
        refresh
            && (!state.forcedBefore
                || nowNanos - state.lastForcedNanos >= forceRefreshInterval.toNanos());
    if (force) {
      state.forcedBefore = true;
      state.lastForcedNanos = nowNanos;
    }
    TagsSnapshot tags = state.tags;
    LoadedSnapshot loaded = state.loaded;
    boolean tagsDue =
        force || tags == null || nowNanos - tags.fetchedAtNanos() >= modelsTtl.toNanos();
    boolean loadedDue =
        force || loaded == null || nowNanos - loaded.fetchedAtNanos() >= loadedTtl.toNanos();
    if (!tagsDue && !loadedDue) {
      return false;
    }
    if (!force
        && state.failedBefore
        && nowNanos - state.lastFailureNanos < loadedTtl.toNanos()) {
      return true; // Ollama failed a moment ago; do not make every caller wait out the timeout
    }

    String address = node.getAddress();
    CompletableFuture<TagsSnapshot> tagsFuture =
        tagsDue
            ? CompletableFuture.supplyAsync(
                MdcPropagation.wrapSupplier(() -> fetchTags(address)), executor)
            : null;
    CompletableFuture<LoadedSnapshot> loadedFuture =
        loadedDue
            ? CompletableFuture.supplyAsync(
                MdcPropagation.wrapSupplier(() -> fetchLoaded(address)), executor)
            : null;

    boolean failed = false;
    if (tagsFuture != null) {
      try {
        state.tags = tagsFuture.join();
      } catch (RuntimeException ex) {
        failed = true;
        logger.warn("Could not read /api/tags from node {}: {}", node.getId(), rootMessage(ex));
      }
    }
    if (loadedFuture != null) {
      try {
        state.loaded = loadedFuture.join();
      } catch (RuntimeException ex) {
        failed = true;
        logger.warn("Could not read /api/ps from node {}: {}", node.getId(), rootMessage(ex));
      }
    }
    state.failedBefore = failed;
    if (failed) {
      state.lastFailureNanos = System.nanoTime();
    }
    return failed;
  }

  private TagsSnapshot fetchTags(String address) {
    try {
      JsonNode response =
          OllamaHttp.get(objectMapper, address, "/api/tags", connectTimeout, readTimeout);
      List<TagEntry> entries = new ArrayList<>();
      for (JsonNode model : response.path("models")) {
        String name = text(model.path("name"));
        if (name == null) {
          name = text(model.path("model"));
        }
        if (name == null) {
          continue;
        }
        JsonNode details = model.path("details");
        entries.add(
            new TagEntry(
                name,
                text(model.path("digest")),
                model.path("size").isNumber() ? model.path("size").asLong() : null,
                text(details.path("family")),
                text(details.path("parameter_size")),
                text(details.path("quantization_level"))));
      }
      return new TagsSnapshot(List.copyOf(entries), System.nanoTime());
    } catch (IOException ex) {
      throw new IllegalStateException(ex.getMessage(), ex);
    }
  }

  private LoadedSnapshot fetchLoaded(String address) {
    try {
      JsonNode response =
          OllamaHttp.get(objectMapper, address, "/api/ps", connectTimeout, readTimeout);
      Map<String, LoadedEntry> byName = new LinkedHashMap<>();
      for (JsonNode model : response.path("models")) {
        String name = text(model.path("name"));
        if (name == null) {
          name = text(model.path("model"));
        }
        if (name == null) {
          continue;
        }
        Long vramMb =
            model.path("size_vram").isNumber()
                ? model.path("size_vram").asLong() / (1024L * 1024L)
                : null;
        byName.put(name, new LoadedEntry(name, vramMb, parseInstant(text(model.path("expires_at")))));
      }
      return new LoadedSnapshot(Map.copyOf(byName), System.nanoTime());
    } catch (IOException ex) {
      throw new IllegalStateException(ex.getMessage(), ex);
    }
  }

  private List<CatalogModel> buildModels(
      AiNode node, TagsSnapshot tags, LoadedSnapshot loaded, boolean fetchDetails) {
    if (tags == null) {
      // No snapshot: heartbeat names only, every detail unknown.
      List<String> names = node.getModels() == null ? List.of() : node.getModels();
      return names.stream()
          .distinct()
          .sorted()
          .map(
              name ->
                  new CatalogModel(
                      name,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      List.of(),
                      loaded != null && loaded.byName().containsKey(name),
                      warmUpRegistry.isInFlight(node.getId(), name),
                      null,
                      null))
          .toList();
    }

    List<CompletableFuture<ModelDetails>> detailFutures =
        tags.models().stream()
            .map(
                tag ->
                    fetchDetails
                        ? CompletableFuture.supplyAsync(
                            MdcPropagation.wrapSupplier(() -> detailsFor(node, tag, true)), executor)
                        : CompletableFuture.completedFuture(detailsFor(node, tag, false)))
            .toList();

    List<CatalogModel> models = new ArrayList<>();
    for (int i = 0; i < tags.models().size(); i++) {
      TagEntry tag = tags.models().get(i);
      ModelDetails details = detailFutures.get(i).join();
      List<String> capabilities = details == null ? List.of() : details.capabilities();
      if (!capabilities.isEmpty() && !capabilities.contains("completion")) {
        continue; // embedding-only model: not chattable
      }
      Integer nativeContext = details == null ? null : details.nativeContext();
      LoadedEntry loadedEntry = loaded == null ? null : loaded.byName().get(tag.name());
      models.add(
          new CatalogModel(
              tag.name(),
              tag.family(),
              tag.parameterSize(),
              tag.quantization(),
              tag.sizeBytes(),
              nativeContext == null ? null : modelInfo.effectiveContext(nativeContext),
              nativeContext,
              capabilities,
              loadedEntry != null,
              warmUpRegistry.isInFlight(node.getId(), tag.name()),
              loadedEntry == null ? null : loadedEntry.expiresAt(),
              loadedEntry == null ? null : loadedEntry.vramMb()));
    }
    models.sort(Comparator.comparing(CatalogModel::name));
    return List.copyOf(models);
  }

  private ModelDetails detailsFor(AiNode node, TagEntry tag, boolean fetch) {
    if (fetch) {
      ModelDetails fetched =
          modelInfo.details(node.getAddress(), tag.name(), tag.digest(), connectTimeout, readTimeout);
      if (fetched != null) {
        return fetched;
      }
    }
    return modelInfo.peek(node.getAddress(), tag.name(), tag.digest());
  }

  private static String text(JsonNode node) {
    return node.isString() && !node.asText().isBlank() ? node.asText() : null;
  }

  private static Instant parseInstant(String value) {
    if (value == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static String rootMessage(Throwable ex) {
    Throwable cause = ex;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause.getMessage();
  }
}
