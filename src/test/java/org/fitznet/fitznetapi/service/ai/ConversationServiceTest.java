package org.fitznet.fitznetapi.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The pure helpers behind titles and search snippets. */
class ConversationServiceTest {

  @Test
  void shouldKeepOnlyTheFirstLineOfAModelTitle() {
    assertEquals("Bell debounce tuning", ConversationService.cleanTitle("Bell debounce tuning\nThis title..."));
  }

  @Test
  void shouldStripQuotesAndATitlePrefix() {
    assertEquals("Hello World", ConversationService.cleanTitle("\"Title: Hello World\""));
    assertEquals("Hello World", ConversationService.cleanTitle("  title:   `Hello World`  "));
    assertEquals("Hello World", ConversationService.cleanTitle("**Hello World**"));
  }

  @Test
  void shouldLimitATitleToEightyCharacters() {
    assertEquals(80, ConversationService.cleanTitle("w".repeat(200)).length());
  }

  @Test
  void shouldTreatAnEmptyReplyAsNoTitle() {
    assertNull(ConversationService.cleanTitle(null));
    assertNull(ConversationService.cleanTitle("   "));
    assertNull(ConversationService.cleanTitle("\"\""));
  }

  @Test
  void shouldMakeASnippetAroundTheFirstCaseInsensitiveMatch() {
    String content = "a".repeat(300) + " Needle " + "b".repeat(300);

    String snippet = ConversationService.snippet(content, "needle");

    assertEquals(160, snippet.length());
    assertTrue(snippet.contains("Needle"));
  }

  @Test
  void shouldReturnShortContentWhole() {
    assertEquals("the quick brown fox", ConversationService.snippet("the quick\nbrown   fox", "brown"));
  }

  @Test
  void shouldKeepAMatchNearTheEndInsideTheWindow() {
    String content = "a".repeat(500) + "needle";

    String snippet = ConversationService.snippet(content, "needle");

    assertEquals(160, snippet.length());
    assertTrue(snippet.endsWith("needle"));
  }
}
