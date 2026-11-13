package org.fitznet.fitznetapi.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** One message of a conversation. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Document("ai_messages")
public class AiMessage {

  public static final int SCHEMA_VERSION = 1;

  public static final String ROLE_USER = "user";
  public static final String ROLE_ASSISTANT = "assistant";

  public static final String STATUS_COMPLETE = "complete";
  public static final String STATUS_STREAMING = "streaming";
  public static final String STATUS_CANCELLED = "cancelled";
  public static final String STATUS_ERROR = "error";

  @Id String id;

  String conversationId;
  /** Denormalised for owner-scoped search. */
  String ownerUsername;
  /** Order within the conversation. */
  int seq;
  /** The previous message; null for the first. */
  String parentId;
  /** {@code user} or {@code assistant}; {@code system} and {@code tool} are reserved. */
  String role;
  String content;
  /** Assistant reasoning text; never replayed to a model. */
  String thinking;

  @Builder.Default List<MessageAttachment> attachments = new ArrayList<>();
  @Builder.Default List<MessageToolCall> toolCalls = new ArrayList<>();
  String toolCallId;

  String status;
  MessageError error;
  /** Assistant messages only. */
  MessageGeneration generation;
  Integer tokenEstimate;
  int schemaVersion;
  Instant createdAt;
  Instant editedAt;
  Instant completedAt;
}
