package org.fitznet.fitznetapi.dto;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.fitznet.fitznetapi.model.NodeStatus;

/** Public-safe view of an {@link org.fitznet.fitznetapi.model.AiNode} — never carries the API key or its hash. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodeDto {
  String id;
  String name;
  NodeStatus status;
  String os;
  List<String> models;
  Double vramGb;
  Instant registeredAt;
  Instant lastHeartbeatAt;
}
