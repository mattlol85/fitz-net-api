package org.fitznet.fitznetapi.service.recipes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bson.types.ObjectId;
import org.fitznet.fitznetapi.dto.requests.RecipeIngredientDto;
import org.fitznet.fitznetapi.dto.requests.RecipeRequestDto;
import org.fitznet.fitznetapi.dto.responses.DeleteRecipeResponseDto;
import org.fitznet.fitznetapi.dto.responses.IngredientIndexDto;
import org.fitznet.fitznetapi.dto.responses.IngredientIndexEntryDto;
import org.fitznet.fitznetapi.dto.responses.RecipeDto;
import org.fitznet.fitznetapi.dto.responses.RecipeListDto;
import org.fitznet.fitznetapi.dto.responses.RecipeRefDto;
import org.fitznet.fitznetapi.dto.responses.RecipeSummaryDto;
import org.fitznet.fitznetapi.model.Recipe;
import org.fitznet.fitznetapi.model.RecipeBook;
import org.fitznet.fitznetapi.model.RecipeIngredient;
import org.fitznet.fitznetapi.repository.RecipeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Recipes in the caller's own book. Every query is scoped to the caller; someone else's recipe is a
 * 404, so its existence is not revealed. On every write the derived {@code ingredientNames} and
 * {@code ingredientTerms} are recomputed by {@link IngredientNormalizer}, and the cover photo is
 * claimed (a replaced or removed photo is deleted).
 */
@Service
public class RecipeService {

  private static final Logger logger = LoggerFactory.getLogger(RecipeService.class);

  /** A personal book holds a few hundred recipes at most; a list or search returns up to this many. */
  public static final int MAX_RESULTS = 500;
  public static final int MAX_SEARCH_INGREDIENTS = 20;
  public static final int MAX_SEARCH_INGREDIENT_CHARS = 80;
  public static final int MAX_QUERY_CHARS = 120;

  private final RecipeRepository recipes;
  private final RecipeBookService books;
  private final RecipeImageService images;

  public RecipeService(RecipeRepository recipes, RecipeBookService books, RecipeImageService images) {
    this.recipes = recipes;
    this.books = books;
    this.images = images;
  }

  // ---- reads -------------------------------------------------------------------------------

  /**
   * The caller's recipes, newest first: every {@code ingredients} value must match (AND), {@code q}
   * is a case-insensitive literal match on the title and {@code chapterId} filters by chapter.
   */
  public RecipeListDto list(String username, List<String> ingredients, String q, String chapterId) {
    List<String> terms = searchTerms(ingredients);
    String title = blankToNull(q);
    if (title != null && title.length() > MAX_QUERY_CHARS) {
      throw badRequest("q must be at most " + MAX_QUERY_CHARS + " characters");
    }
    String chapter = blankToNull(chapterId);
    List<RecipeSummaryDto> items =
        recipes.search(username, terms, title, chapter, MAX_RESULTS).stream()
            .map(RecipeService::toSummary)
            .toList();
    long total =
        items.size() < MAX_RESULTS ? items.size() : recipes.countMatching(username, terms, title, chapter);
    return new RecipeListDto(items, total);
  }

  public IngredientIndexDto ingredientIndex(String username) {
    return new IngredientIndexDto(
        recipes.ingredientIndex(username).stream()
            .map(
                row ->
                    new IngredientIndexEntryDto(
                        row.name(),
                        row.recipes().stream().map(r -> new RecipeRefDto(r.id(), r.title())).toList()))
            .toList());
  }

  public RecipeDto get(String username, String id) {
    return toDto(load(username, id));
  }

  // ---- writes ------------------------------------------------------------------------------

  public RecipeDto create(String username, RecipeRequestDto request) {
    RecipeBook book = books.getOrCreate(username);
    Recipe recipe = fromRequest(book, request);
    // The id is chosen up front so the photo can be claimed before the recipe exists.
    recipe.setId(new ObjectId().toHexString());
    if (recipe.getImageId() != null) {
      images.claim(username, recipe.getImageId(), recipe.getId());
    }
    Instant now = now();
    recipe.setOwnerUsername(username);
    recipe.setBookId(book.getId());
    recipe.setCreatedAt(now);
    recipe.setUpdatedAt(now);
    Recipe saved = recipes.save(recipe);
    logger.info("Created recipe {} in book {}", saved.getId(), book.getId());
    return toDto(saved);
  }

  /** Replaces the recipe's content; {@code createdAt} (the date added) never changes. */
  public RecipeDto update(String username, String id, RecipeRequestDto request) {
    Recipe existing = load(username, id);
    RecipeBook book = books.getOrCreate(username);
    Recipe recipe = fromRequest(book, request);
    String oldImageId = existing.getImageId();
    if (recipe.getImageId() != null && !recipe.getImageId().equals(oldImageId)) {
      images.claim(username, recipe.getImageId(), existing.getId());
    }
    recipe.setId(existing.getId());
    recipe.setOwnerUsername(existing.getOwnerUsername());
    recipe.setBookId(existing.getBookId());
    recipe.setCreatedAt(existing.getCreatedAt());
    recipe.setUpdatedAt(now());
    Recipe saved = recipes.save(recipe);
    if (oldImageId != null && !oldImageId.equals(saved.getImageId())) {
      images.delete(oldImageId);
    }
    return toDto(saved);
  }

  /** Deletes the recipe and its photo. */
  public DeleteRecipeResponseDto delete(String username, String id) {
    Recipe existing = load(username, id);
    recipes.deleteById(existing.getId());
    images.deleteForRecipe(existing.getId());
    if (existing.getImageId() != null) {
      images.delete(existing.getImageId());
    }
    logger.info("Deleted recipe {}", existing.getId());
    return new DeleteRecipeResponseDto(existing.getId(), true);
  }

  // ---- helpers -----------------------------------------------------------------------------

  private Recipe load(String username, String id) {
    return recipes
        .findByIdAndOwnerUsername(id, username)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipe not found"));
  }

  /** The normalized, validated content of a request (no id, owner or timestamps yet). */
  private static Recipe fromRequest(RecipeBook book, RecipeRequestDto request) {
    String chapterId = blankToNull(request.getChapterId());
    if (chapterId != null && !RecipeBookService.hasChapter(book, chapterId)) {
      throw badRequest("Unknown chapter id");
    }
    List<RecipeIngredient> ingredients = new ArrayList<>();
    for (RecipeIngredientDto row : request.getIngredients()) {
      String name = row.getName() == null ? "" : row.getName().trim();
      if (IngredientNormalizer.normalize(name).isEmpty()) {
        throw badRequest("Ingredient name must contain a letter or digit");
      }
      ingredients.add(
          new RecipeIngredient(
              blankToNull(row.getQuantity()), blankToNull(row.getUnit()), name, blankToNull(row.getNote())));
    }
    List<String> instructions = request.getInstructions().stream().map(String::trim).toList();
    List<String> names = IngredientNormalizer.names(ingredients.stream().map(RecipeIngredient::name).toList());
    return Recipe.builder()
        .chapterId(chapterId)
        .title(request.getTitle().trim())
        .servings(request.getServings())
        .prepMinutes(request.getPrepMinutes())
        .cookMinutes(request.getCookMinutes())
        .ingredients(ingredients)
        .instructions(instructions)
        .imageId(blankToNull(request.getImageId()))
        .ingredientNames(names)
        .ingredientTerms(IngredientNormalizer.terms(names))
        .build();
  }

  private static List<String> searchTerms(List<String> ingredients) {
    if (ingredients == null || ingredients.isEmpty()) {
      return List.of();
    }
    if (ingredients.size() > MAX_SEARCH_INGREDIENTS) {
      throw badRequest("At most " + MAX_SEARCH_INGREDIENTS + " ingredients per search");
    }
    Set<String> terms = new LinkedHashSet<>();
    for (String raw : ingredients) {
      if (raw != null && raw.length() > MAX_SEARCH_INGREDIENT_CHARS) {
        throw badRequest("ingredient must be at most " + MAX_SEARCH_INGREDIENT_CHARS + " characters");
      }
      String term = IngredientNormalizer.normalize(raw);
      if (!term.isEmpty()) {
        terms.add(term);
      }
    }
    return new ArrayList<>(terms);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  static RecipeSummaryDto toSummary(Recipe r) {
    return new RecipeSummaryDto(
        r.getId(),
        r.getTitle(),
        r.getChapterId(),
        r.getImageId(),
        r.getServings(),
        r.getPrepMinutes(),
        r.getCookMinutes(),
        Objects.requireNonNullElse(r.getIngredientNames(), List.of()),
        r.getCreatedAt());
  }

  static RecipeDto toDto(Recipe r) {
    List<RecipeIngredientDto> ingredients =
        r.getIngredients() == null
            ? List.of()
            : r.getIngredients().stream()
                .map(i -> new RecipeIngredientDto(i.quantity(), i.unit(), i.name(), i.note()))
                .toList();
    return new RecipeDto(
        r.getId(),
        r.getTitle(),
        r.getChapterId(),
        r.getImageId(),
        r.getServings(),
        r.getPrepMinutes(),
        r.getCookMinutes(),
        Objects.requireNonNullElse(r.getIngredientNames(), List.of()),
        r.getCreatedAt(),
        ingredients,
        Objects.requireNonNullElse(r.getInstructions(), List.of()),
        r.getUpdatedAt());
  }

  /** Mongo stores milliseconds; truncating keeps a write's response equal to later reads. */
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }
}
