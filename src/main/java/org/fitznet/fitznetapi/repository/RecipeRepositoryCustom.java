package org.fitznet.fitznetapi.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.fitznet.fitznetapi.model.Recipe;

/** {@code MongoTemplate} queries over one owner's recipes. */
public interface RecipeRepositoryCustom {

  /** One entry of the ingredient index: a normalized name and the recipes that use it. */
  record IndexRow(String name, List<RecipeRef> recipes) {}

  /** A recipe id and title. */
  record RecipeRef(String id, String title) {}

  /**
   * The owner's recipes that contain every one of {@code terms} (normalized ingredient terms), whose
   * title contains {@code titleQuery} (case-insensitive literal; null = any) and that are filed in
   * {@code chapterId} (null = any), newest first, at most {@code limit}. Ingredient rows and steps
   * are not loaded.
   */
  List<Recipe> search(
      String ownerUsername, List<String> terms, String titleQuery, String chapterId, int limit);

  /** How many recipes {@link #search} would match without a limit. */
  long countMatching(String ownerUsername, List<String> terms, String titleQuery, String chapterId);

  /** Every normalized ingredient name of the owner's recipes with the recipes using it, A to Z. */
  List<IndexRow> ingredientIndex(String ownerUsername);

  /** Number of recipes per chapter id (Unsorted recipes are counted under a null key). */
  Map<String, Long> countByChapter(String ownerUsername);

  /** Moves the owner's recipes in any of {@code chapterIds} to Unsorted; returns how many moved. */
  long unsetChapter(String ownerUsername, Collection<String> chapterIds);
}
