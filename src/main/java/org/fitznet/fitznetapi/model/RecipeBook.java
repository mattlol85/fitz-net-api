package org.fitznet.fitznetapi.model;

import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A user's personal recipe book (Lori-Net): its identity, cover and chapter layout. One per user
 * (unique index {@code owner_unique}), created lazily on first read. The recipes live in their own
 * collection ({@link Recipe}).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Document("recipe_books")
public class RecipeBook {

  @Id String id;

  String ownerUsername;
  String title;
  /** One of {@code rose, burgundy, forest, navy, tan, charcoal}. */
  String coverColor;
  /** Ordered: the list order is the book order. */
  List<RecipeChapter> chapters;
  Instant createdAt;
  Instant updatedAt;
}
