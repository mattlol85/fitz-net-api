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

/**
 * A Matt-GPT preset: a named bundle of model, system prompt and sampling settings. {@code private}
 * presets belong to their creator; {@code shared} ones are created by an ADMIN and visible by
 * permission.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Document("ai_presets")
public class AiPreset {

  public static final String PRIVATE = "private";
  public static final String SHARED = "shared";

  @Id String id;

  /** The creator. */
  String ownerUsername;
  String name;
  String description;
  /** Nullable: "whatever model is selected". */
  String model;
  /** Nullable: Auto. Only meaningful with a model. */
  String nodeId;
  String systemPrompt;
  /** 0 to 2; nullable: the model default. */
  Double temperature;
  /** 512 to 131072; nullable: the model default. Capped at run time. */
  Integer numCtx;
  /** {@code private} or {@code shared}. */
  String visibility;
  /** Shared only: null means everyone with AI access. */
  String requiredPermission;
  Instant createdAt;
  Instant updatedAt;
}
