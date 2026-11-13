package org.fitznet.fitznetapi.model;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An uploaded image. It starts unattached with {@code expiresAt} set, so a Mongo TTL index removes
 * an upload that is never used; attaching it to a message clears {@code expiresAt}.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@ToString(exclude = "data")
@Document("ai_attachments")
public class AiAttachment {

  public static final String KIND_IMAGE = "image";

  @Id String id;

  String ownerUsername;
  /** Null until attached. */
  String conversationId;
  /** Null until attached. */
  String messageId;
  String kind;
  String mimeType;
  String name;
  long sizeBytes;
  byte[] data;
  Instant createdAt;
  /** Set on upload, unset (null) when attached. */
  Instant expiresAt;

  public boolean attached() {
    return messageId != null;
  }
}
