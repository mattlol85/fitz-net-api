package org.fitznet.fitznetapi.service.ai;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fitznet.fitznetapi.service.OllamaModelInfo;
import org.springframework.stereotype.Service;

/**
 * Resolves a target {@code {model, nodeId | null}} to a node (contract section 4).
 *
 * <p>{@code nodeId == null} is Auto: the best candidate by {@link AutoOrder}. An explicit node is
 * never substituted silently: if it is unknown, OFFLINE or has no address the outcome is
 * {@code NODE_OFFLINE}; if it does not have the model, {@code MODEL_NOT_ON_NODE}. Each failure
 * lists the other nodes that have the model, in Auto order, as {@code alternatives}.
 */
@Service
public class ModelRouter {

  private final ModelCatalogService catalogService;
  private final OllamaModelInfo modelInfo;

  public ModelRouter(ModelCatalogService catalogService, OllamaModelInfo modelInfo) {
    this.catalogService = catalogService;
    this.modelInfo = modelInfo;
  }

  /**
   * @param model exact model name, e.g. {@code llama3.1:8b}
   * @param nodeId explicit node, or null for Auto
   * @param excludedNodeIds nodes already tried this turn; only Auto honours it
   */
  public RouteOutcome route(String model, String nodeId, Set<String> excludedNodeIds) {
    if (model == null || model.isBlank()) {
      return new RouteOutcome.Failed(
          ChatErrorCodes.NO_NODE_AVAILABLE, "No model was selected.", false, List.of());
    }
    return nodeId == null || nodeId.isBlank()
        ? routeAuto(model, excludedNodeIds == null ? Set.of() : excludedNodeIds)
        : routeExplicit(model, nodeId);
  }

  /** Nodes that could serve {@code model} right now, best first, minus {@code excludedNodeIds}. */
  public List<Alternative> alternatives(String model, Set<String> excludedNodeIds) {
    return toAlternatives(catalogService.catalog(false), model, excludedNodeIds);
  }

  private RouteOutcome routeAuto(String model, Set<String> excluded) {
    Catalog catalog = catalogService.catalog(false);
    List<CatalogNode> candidates = AutoOrder.candidates(catalog, model, excluded);
    if (candidates.isEmpty()) {
      return new RouteOutcome.Failed(
          ChatErrorCodes.NO_NODE_AVAILABLE,
          "No node is available for " + model + " right now.",
          true,
          List.of());
    }
    CatalogNode node = candidates.get(0);
    return new RouteOutcome.Routed(toTarget(node, node.model(model).orElseThrow(), true));
  }

  private RouteOutcome routeExplicit(String model, String nodeId) {
    Optional<CatalogNode> found = catalogService.node(nodeId, false);
    if (found.isEmpty() || !found.get().routable()) {
      String message =
          found.isEmpty() ? "That node is not registered." : found.get().name() + " is offline.";
      return new RouteOutcome.Failed(
          ChatErrorCodes.NODE_OFFLINE, message, true, alternatives(model, Set.of(nodeId)));
    }
    CatalogNode node = found.get();
    Optional<CatalogModel> catalogModel = node.model(model);
    if (catalogModel.isEmpty()) {
      return new RouteOutcome.Failed(
          ChatErrorCodes.MODEL_NOT_ON_NODE,
          node.name() + " does not have " + model + ".",
          false,
          alternatives(model, Set.of(nodeId)));
    }
    return new RouteOutcome.Routed(toTarget(node, catalogModel.get(), false));
  }

  private RoutedTarget toTarget(CatalogNode node, CatalogModel model, boolean auto) {
    return new RoutedTarget(
        node.id(),
        node.name(),
        node.address(),
        model.name(),
        auto,
        model.capabilities(),
        model.contextLength() != null ? model.contextLength() : modelInfo.maxNumCtx(),
        model.loaded());
  }

  private static List<Alternative> toAlternatives(
      Catalog catalog, String model, Set<String> excluded) {
    return AutoOrder.candidates(catalog, model, excluded).stream()
        .map(
            n ->
                new Alternative(
                    n.id(),
                    n.name(),
                    model,
                    n.model(model).map(CatalogModel::loaded).orElse(false),
                    n.status()))
        .toList();
  }
}
