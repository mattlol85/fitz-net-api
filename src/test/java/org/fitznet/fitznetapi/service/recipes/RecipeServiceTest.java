package org.fitznet.fitznetapi.service.recipes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.fitznet.fitznetapi.dto.requests.RecipeIngredientDto;
import org.fitznet.fitznetapi.dto.requests.RecipeRequestDto;
import org.fitznet.fitznetapi.dto.responses.DeleteRecipeResponseDto;
import org.fitznet.fitznetapi.dto.responses.RecipeDto;
import org.fitznet.fitznetapi.model.Recipe;
import org.fitznet.fitznetapi.model.RecipeBook;
import org.fitznet.fitznetapi.model.RecipeChapter;
import org.fitznet.fitznetapi.model.RecipeIngredient;
import org.fitznet.fitznetapi.repository.RecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RecipeServiceTest {

  @Mock private RecipeRepository recipes;
  @Mock private RecipeBookService books;
  @Mock private RecipeImageService images;
  @InjectMocks private RecipeService service;

  private RecipeBook book;

  @BeforeEach
  void setUp() {
    book =
        RecipeBook.builder()
            .id("book-1")
            .ownerUsername("alice")
            .chapters(List.of(new RecipeChapter("ch1", "Breakfast", "amber")))
            .build();
  }

  private static RecipeRequestDto request(String chapterId, String imageId, String... ingredientNames) {
    List<RecipeIngredientDto> rows = new ArrayList<>();
    for (String name : ingredientNames) {
      rows.add(new RecipeIngredientDto(" 2 ", "", name, null));
    }
    return new RecipeRequestDto(
        "  Pancakes ", chapterId, 4, 10, 15, rows, List.of(" Mix. ", "Fry."), imageId);
  }

  private Recipe stored(String imageId) {
    return Recipe.builder()
        .id("r1")
        .ownerUsername("alice")
        .bookId("book-1")
        .title("Old")
        .ingredients(List.of(new RecipeIngredient(null, null, "salt", null)))
        .instructions(List.of("Stir."))
        .imageId(imageId)
        .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
        .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
        .build();
  }

  private void savesReturnTheirArgument() {
    when(recipes.save(any(Recipe.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void shouldNormalizeAndDeriveIngredientFieldsOnCreate() {
    when(books.getOrCreate("alice")).thenReturn(book);
    savesReturnTheirArgument();

    RecipeDto dto = service.create("alice", request("ch1", null, "Eggs", "Plain Flour", "eggs"));

    ArgumentCaptor<Recipe> saved = ArgumentCaptor.forClass(Recipe.class);
    verify(recipes).save(saved.capture());
    Recipe recipe = saved.getValue();
    assertEquals("alice", recipe.getOwnerUsername());
    assertEquals("book-1", recipe.getBookId());
    assertEquals("Pancakes", recipe.getTitle());
    assertEquals("ch1", recipe.getChapterId());
    assertEquals(List.of("egg", "plain flour"), recipe.getIngredientNames());
    assertTrue(recipe.getIngredientTerms().containsAll(List.of("egg", "plain flour", "flour", "plain")));
    assertEquals("2", recipe.getIngredients().get(0).quantity());
    assertNull(recipe.getIngredients().get(0).unit());
    assertEquals(List.of("Mix.", "Fry."), recipe.getInstructions());
    assertEquals(recipe.getCreatedAt(), recipe.getUpdatedAt());
    assertEquals(recipe.getId(), dto.id());
    verify(images, never()).claim(anyString(), anyString(), anyString());
  }

  @Test
  void shouldRejectAChapterThatIsNotInTheBook() {
    when(books.getOrCreate("alice")).thenReturn(book);

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> service.create("alice", request("nope", null, "egg")));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    verify(recipes, never()).save(any());
  }

  @Test
  void shouldRejectAnIngredientNameWithoutLetters() {
    when(books.getOrCreate("alice")).thenReturn(book);

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> service.create("alice", request(null, null, "!!!")));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void shouldClaimTheImageForTheNewRecipeBeforeSaving() {
    when(books.getOrCreate("alice")).thenReturn(book);
    savesReturnTheirArgument();

    RecipeDto dto = service.create("alice", request(null, "img-1", "egg"));

    verify(images).claim("alice", "img-1", dto.id());
    assertEquals("img-1", dto.imageId());
  }

  @Test
  void shouldKeepCreatedAtAndDeleteTheReplacedImageOnUpdate() {
    Recipe existing = stored("img-old");
    when(recipes.findByIdAndOwnerUsername("r1", "alice")).thenReturn(Optional.of(existing));
    when(books.getOrCreate("alice")).thenReturn(book);
    savesReturnTheirArgument();

    RecipeDto dto = service.update("alice", "r1", request(null, "img-new", "egg"));

    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), dto.createdAt());
    assertTrue(dto.updatedAt().isAfter(dto.createdAt()));
    assertEquals("Pancakes", dto.title());
    verify(images).claim("alice", "img-new", "r1");
    verify(images).delete("img-old");
  }

  @Test
  void shouldNotReclaimOrDeleteAnUnchangedImage() {
    when(recipes.findByIdAndOwnerUsername("r1", "alice")).thenReturn(Optional.of(stored("img-1")));
    when(books.getOrCreate("alice")).thenReturn(book);
    savesReturnTheirArgument();

    service.update("alice", "r1", request(null, "img-1", "egg"));

    verify(images, never()).claim(anyString(), anyString(), anyString());
    verify(images, never()).delete(anyString());
  }

  @Test
  void shouldDeleteTheImageWhenItIsRemovedFromTheRecipe() {
    when(recipes.findByIdAndOwnerUsername("r1", "alice")).thenReturn(Optional.of(stored("img-1")));
    when(books.getOrCreate("alice")).thenReturn(book);
    savesReturnTheirArgument();

    RecipeDto dto = service.update("alice", "r1", request(null, null, "egg"));

    assertNull(dto.imageId());
    verify(images).delete("img-1");
  }

  @Test
  void shouldReturnNotFoundForSomeoneElsesRecipe() {
    when(recipes.findByIdAndOwnerUsername("r1", "bob")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.get("bob", "r1"));
    assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    assertThrows(ResponseStatusException.class, () -> service.delete("bob", "r1"));
    verify(recipes, never()).deleteById(anyString());
  }

  @Test
  void shouldDeleteTheRecipeAndItsImage() {
    when(recipes.findByIdAndOwnerUsername("r1", "alice")).thenReturn(Optional.of(stored("img-1")));

    DeleteRecipeResponseDto dto = service.delete("alice", "r1");

    assertEquals(new DeleteRecipeResponseDto("r1", true), dto);
    verify(recipes).deleteById("r1");
    verify(images).deleteForRecipe("r1");
    verify(images).delete("img-1");
  }

  @Test
  void shouldNormalizeSearchIngredientsAndBlankFilters() {
    when(recipes.search(eq("alice"), any(), any(), any(), eq(RecipeService.MAX_RESULTS)))
        .thenReturn(List.of());

    service.list("alice", List.of("Eggs", " FLOUR ", "eggs", "  "), "  ", "");

    verify(recipes).search("alice", List.of("egg", "flour"), null, null, RecipeService.MAX_RESULTS);
    verify(recipes, never()).countMatching(any(), any(), any(), any());
  }

  @Test
  void shouldRejectTooManyOrTooLongSearchInputs() {
    List<String> many = Collections.nCopies(RecipeService.MAX_SEARCH_INGREDIENTS + 1, "egg");
    assertThrows(ResponseStatusException.class, () -> service.list("alice", many, null, null));
    assertThrows(
        ResponseStatusException.class,
        () -> service.list("alice", List.of("x".repeat(81)), null, null));
    assertThrows(
        ResponseStatusException.class, () -> service.list("alice", null, "q".repeat(121), null));
  }
}
