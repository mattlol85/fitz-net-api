package org.fitznet.fitznetapi.service.ai;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.fitznet.fitznetapi.model.NodeStatus;

/**
 * The Auto routing order (contract 4.1). Candidates are nodes that are not OFFLINE, have an
 * address and list the model, minus any already tried this turn, ordered by:
 *
 * <ol>
 *   <li>warm first (loaded, or a warm-up is in flight)
 *   <li>fewest generations in flight, then heartbeat status ONLINE before BUSY
 *   <li>fits: model size at most the free VRAM (unknown counts as fits)
 *   <li>most free VRAM (unknown last)
 *   <li>node name, then id
 * </ol>
 */
final class AutoOrder {

  private static final long MIB = 1024L * 1024L;

  private AutoOrder() {}

  static List<CatalogNode> candidates(Catalog catalog, String model, Set<String> excludedNodeIds) {
    return catalog.nodes().stream()
        .filter(CatalogNode::routable)
        .filter(n -> !excludedNodeIds.contains(n.id()))
        .filter(n -> n.model(model).isPresent())
        .sorted(comparator(model))
        .toList();
  }

  static Comparator<CatalogNode> comparator(String model) {
    return Comparator.comparingInt((CatalogNode n) -> isLoaded(n, model) ? 0 : 1)
        .thenComparingInt(CatalogNode::activeGenerations)
        .thenComparingInt(n -> n.storedStatus() == NodeStatus.BUSY ? 1 : 0)
        .thenComparingInt(n -> fits(n, model) ? 0 : 1)
        .thenComparing(
            CatalogNode::freeVramMb, Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(CatalogNode::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
        .thenComparing(CatalogNode::name, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(CatalogNode::id);
  }

  private static boolean isLoaded(CatalogNode node, String model) {
    return node.model(model).map(CatalogModel::loaded).orElse(false);
  }

  private static boolean fits(CatalogNode node, String model) {
    Integer free = node.freeVramMb();
    Long size = node.model(model).map(CatalogModel::sizeBytes).orElse(null);
    return free == null || size == null || size <= free * MIB;
  }
}
