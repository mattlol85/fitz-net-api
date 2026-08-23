package org.fitznet.fitznetapi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Document("ai_nodes")
@ToString(exclude = "apiKeyHash")
public class AiNode {

  @Id String id;

  String name;
  @JsonIgnore String apiKeyHash;
  NodeStatus status;
  String os;
  List<String> models;
  Double vramGb;
  /** The node's self-reported "host:port" for its Ollama instance (e.g. "192.168.1.50:11434"). Internal only - never exposed on {@link org.fitznet.fitznetapi.dto.NodeDto}. */
  String address;
  Instant registeredAt;
  Instant lastHeartbeatAt;
}
