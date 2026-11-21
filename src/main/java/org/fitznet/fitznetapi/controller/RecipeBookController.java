package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import org.fitznet.fitznetapi.dto.requests.UpdateRecipeBookRequestDto;
import org.fitznet.fitznetapi.dto.responses.RecipeBookDto;
import org.fitznet.fitznetapi.service.recipes.RecipeBookService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's Lori-Net recipe book (cover and chapters). Needs {@code LORINET_ACCESS} or
 * {@code ADMIN}; everyone only ever sees their own book. The gRPC {@code RecipeBookService} calls
 * the same methods.
 */
@RestController
@RequestMapping("/recipe-book")
@PreAuthorize("hasAnyAuthority('PERM_LORINET_ACCESS','PERM_ADMIN')")
public class RecipeBookController {

  private final RecipeBookService recipeBookService;

  public RecipeBookController(RecipeBookService recipeBookService) {
    this.recipeBookService = recipeBookService;
  }

  /** The caller's book, created with the default chapters on first use. */
  @GetMapping
  public RecipeBookDto getRecipeBook() {
    return recipeBookService.getBook(currentUsername());
  }

  /** Replaces the title, cover colour and the whole chapter list (in book order). */
  @PutMapping
  public RecipeBookDto updateRecipeBook(@RequestBody @Valid UpdateRecipeBookRequestDto request) {
    return recipeBookService.update(currentUsername(), request);
  }

  private static String currentUsername() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
