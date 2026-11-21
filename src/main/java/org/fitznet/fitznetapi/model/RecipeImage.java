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
 * A recipe's cover photo. An upload starts unclaimed with {@code expiresAt} set, so the
 * {@code expires_at_ttl} index removes it if no recipe ever uses it; saving a recipe that references
 * it sets {@code recipeId} and unsets {@code expiresAt}.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@ToString(exclude = "data")
@Document("recipe_images")
public class RecipeImage {

  @Id String id;

  String ownerUsername;
  /** Null until a recipe claims it. */
  String recipeId;
  String mimeType;
  long sizeBytes;
  /** Null when the header could not be read. */
  Integer width;
  /** Null when the header could not be read. */
  Integer height;
  byte[] data;
  Instant createdAt;
  /** Set on upload, unset (null) when claimed. */
  Instant expiresAt;
}
