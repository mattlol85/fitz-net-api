package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import java.util.List;
import org.fitznet.fitznetapi.dto.requests.RecipeRequestDto;
import org.fitznet.fitznetapi.dto.responses.DeleteRecipeResponseDto;
import org.fitznet.fitznetapi.dto.responses.IngredientIndexDto;
import org.fitznet.fitznetapi.dto.responses.RecipeDto;
import org.fitznet.fitznetapi.dto.responses.RecipeListDto;
import org.fitznet.fitznetapi.service.recipes.RecipeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recipes in the caller's own book. Needs {@code LORINET_ACCESS} or {@code ADMIN}; someone else's
 * recipe is a 404. The gRPC {@code RecipeService} calls the same methods.
 */
@RestController
@RequestMapping("/recipes")
@PreAuthorize("hasAnyAuthority('PERM_LORINET_ACCESS','PERM_ADMIN')")
public class RecipeController {

  private final RecipeService recipeService;

  public RecipeController(RecipeService recipeService) {
    this.recipeService = recipeService;
  }

  /**
   * Newest first, at most 500. Several {@code ingredient} values are ANDed ("egg and flour"), plural
   * and case insensitive; {@code q} matches the title; {@code chapterId} filters by chapter.
   */
  @GetMapping
  public RecipeListDto listRecipes(
      @RequestParam(name = "ingredient", required = false) List<String> ingredient,
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "chapterId", required = false) String chapterId) {
    return recipeService.list(currentUsername(), ingredient, q, chapterId);
  }

  /** The back-of-book index: every ingredient name with the recipes using it, A to Z. */
  @GetMapping("/ingredients")
  public IngredientIndexDto getIngredientIndex() {
    return recipeService.ingredientIndex(currentUsername());
  }

  @GetMapping("/{id}")
  public RecipeDto getRecipe(@PathVariable String id) {
    return recipeService.get(currentUsername(), id);
  }

  @PostMapping
  public RecipeDto createRecipe(@RequestBody @Valid RecipeRequestDto request) {
    return recipeService.create(currentUsername(), request);
  }

  @PutMapping("/{id}")
  public RecipeDto updateRecipe(@PathVariable String id, @RequestBody @Valid RecipeRequestDto request) {
    return recipeService.update(currentUsername(), id, request);
  }

  /** Deletes the recipe and its photo. */
  @DeleteMapping("/{id}")
  public DeleteRecipeResponseDto deleteRecipe(@PathVariable String id) {
    return recipeService.delete(currentUsername(), id);
  }

  private static String currentUsername() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
