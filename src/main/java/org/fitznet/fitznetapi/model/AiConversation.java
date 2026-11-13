package org.fitznet.fitznetapi.model;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** A Matt-GPT conversation. Owned by {@code ownerUsername} (canonical lowercase). */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Document("ai_conversations")
public class AiConversation {

  public static final int SCHEMA_VERSION = 1;
  public static final String DEFAULT_TITLE = "New chat";
  public static final String TITLE_DEFAULT = "default";
  public static final String TITLE_AUTO = "auto";
  public static final String TITLE_USER = "user";

  @Id String id;

  String ownerUsername;
  String title;
  /** {@code default}, {@code auto} or {@code user}. */
  String titleSource;
  boolean pinned;
  /** Nullable until first chosen. */
  ModelTarget target;
  String presetId;
  String systemPrompt;
  StoredSummary summary;
  /** The sequence number the next message gets. */
  int nextSeq;
  /** Always the last message in v2; the hook for branching later. */
  String activeLeafId;
  int messageCount;
  int schemaVersion;
  Instant createdAt;
  Instant updatedAt;
}
