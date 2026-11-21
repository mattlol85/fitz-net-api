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
 * One recipe in a user's book. Ingredients are embedded (always read and written together); the
 * cover photo's bytes live in {@link RecipeImage}. {@code ingredientNames} and
 * {@code ingredientTerms} are derived by {@code IngredientNormalizer} on every write and drive the
 * ingredient index and search.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Document("recipes")
public class Recipe {

  @Id String id;

  /** Denormalized from the book; scopes every query. */
  String ownerUsername;
  String bookId;
  /** Null: the "Unsorted" chapter at the back of the book. */
  String chapterId;
  String title;
  Integer servings;
  Integer prepMinutes;
  Integer cookMinutes;
  List<RecipeIngredient> ingredients;
  List<String> instructions;
  /** The {@link RecipeImage} id of the cover photo, or null. */
  String imageId;
  /** Derived: the normalized full ingredient names ({@code "chicken thigh"}). */
  List<String> ingredientNames;
  /** Derived: the names plus their words and sub-phrases, matched by ingredient search. */
  List<String> ingredientTerms;
  /** The "date added": set by the server on create and never changed. */
  Instant createdAt;
  Instant updatedAt;
}
