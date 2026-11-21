package org.fitznet.fitznetapi.service.recipes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class IngredientNormalizerTest {

  @Test
  void shouldLowercaseStripPunctuationAndCollapseWhitespace() {
    assertEquals("all purpose flour", IngredientNormalizer.normalize("  All-Purpose   FLOUR! "));
    assertEquals("bakers yeast", IngredientNormalizer.normalize("Baker's yeast"));
    assertEquals("jalapeño", IngredientNormalizer.normalize("Jalapeño"));
  }

  @Test
  void shouldSingularizeOnlyTheLastWord() {
    assertEquals("egg", IngredientNormalizer.normalize("Eggs"));
    assertEquals("tomato", IngredientNormalizer.normalize("tomatoes"));
    assertEquals("berry", IngredientNormalizer.normalize("berries"));
    assertEquals("peach", IngredientNormalizer.normalize("peaches"));
    assertEquals("radish", IngredientNormalizer.normalize("radishes"));
    assertEquals("box", IngredientNormalizer.normalize("boxes"));
    assertEquals("glass", IngredientNormalizer.normalize("glasses"));
    assertEquals("cheese", IngredientNormalizer.normalize("cheeses"));
    assertEquals("chicken thigh", IngredientNormalizer.normalize("Chicken Thighs"));
    assertEquals("brussels sprout", IngredientNormalizer.normalize("brussels sprouts"));
  }

  @Test
  void shouldKeepWordsThatOnlyLookPlural() {
    assertEquals("swiss", IngredientNormalizer.normalize("Swiss"));
    assertEquals("molasses", IngredientNormalizer.normalize("molasses"));
    assertEquals("asparagus", IngredientNormalizer.normalize("asparagus"));
    assertEquals("citrus", IngredientNormalizer.normalize("citrus"));
    assertEquals("gas", IngredientNormalizer.normalize("gas"));
    assertEquals("cookie", IngredientNormalizer.normalize("cookies"));
    assertEquals("bay leaf", IngredientNormalizer.normalize("bay leaves"));
  }

  @Test
  void shouldReturnEmptyForNothingButPunctuation() {
    assertEquals("", IngredientNormalizer.normalize(" -- !! "));
    assertEquals("", IngredientNormalizer.normalize(null));
  }

  @Test
  void shouldDeduplicateNamesInOrder() {
    assertEquals(
        List.of("egg", "flour"),
        IngredientNormalizer.names(List.of("Eggs", "flour", "egg", "!!!")));
  }

  @Test
  void shouldBuildTermsFromNamesWordsAndPhrases() {
    List<String> terms = IngredientNormalizer.terms(List.of("boneless chicken thigh", "egg"));
    assertTrue(terms.contains("boneless chicken thigh"));
    assertTrue(terms.contains("chicken thigh"));
    assertTrue(terms.contains("boneless chicken"));
    assertTrue(terms.contains("chicken"));
    assertTrue(terms.contains("thigh"));
    assertTrue(terms.contains("egg"));
  }

  @Test
  void shouldSingularizeEveryWordOfTheTermsButSkipShortWords() {
    List<String> terms = IngredientNormalizer.terms(List.of("eggs of quail"));
    assertTrue(terms.contains("egg"), terms.toString());
    assertTrue(terms.contains("quail"));
    assertFalse(terms.contains("of"));
    assertTrue(terms.contains("egg of"));
  }

  @Test
  void shouldMatchSearchInputAgainstStoredTerms() {
    List<String> stored =
        IngredientNormalizer.terms(IngredientNormalizer.names(List.of("Large Eggs", "Tomatoes")));
    assertTrue(stored.contains(IngredientNormalizer.normalize("EGGS")));
    assertTrue(stored.contains(IngredientNormalizer.normalize("egg")));
    assertTrue(stored.contains(IngredientNormalizer.normalize("Tomato")));
    assertTrue(stored.contains(IngredientNormalizer.normalize("large egg")));
  }
}
