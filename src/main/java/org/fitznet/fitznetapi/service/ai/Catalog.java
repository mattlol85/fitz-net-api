package org.fitznet.fitznetapi.service.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Every registered node with its models, sorted by node name. */
public record Catalog(Instant generatedAt, List<CatalogNode> nodes) {

  public Optional<CatalogNode> node(String nodeId) {
    return nodes.stream().filter(n -> n.id().equals(nodeId)).findFirst();
  }
}
