package org.fitznet.fitznetapi.integration;

import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.seedAssistant;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.seedConversation;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.seedUser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.fitznet.fitznetapi.model.AiConversation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Search snippets come from one query for the whole page, not one per row. */
class AiSearchSnippetIntegrationTest extends AiChatTestBase {

  private AiConversation withMessages(String title, String... contents) {
    AiConversation conversation = seedConversation(conversations, "alice", title, null);
    for (int i = 0; i < contents.length; i++) {
      if (i % 2 == 0) {
        seedUser(conversations, messages, conversation, contents[i]);
      } else {
        seedAssistant(conversations, messages, conversation, contents[i]);
      }
    }
    return conversation;
  }

  @Test
  void shouldReturnTheFirstMatchingMessageOfEachConversationInOneCall() {
    AiConversation first = withMessages("one", "nothing here", "has a NEEDLE one", "another needle two");
    AiConversation second = withMessages("two", "needle early", "needle late");
    AiConversation none = withMessages("three", "nothing", "at all");

    Map<String, String> hits =
        conversations.firstMatchingContents(
            List.of(first.getId(), second.getId(), none.getId()), "needle");

    assertEquals(2, hits.size());
    assertEquals("has a NEEDLE one", hits.get(first.getId()));
    assertEquals("needle early", hits.get(second.getId()));
    assertFalse(hits.containsKey(none.getId()));
  }

  @Test
  void shouldTreatTheTermLiterallyAndIgnoreConversationsThatWereNotAsked() {
    AiConversation dotted = withMessages("dotted", "version a.c here");
    AiConversation plain = withMessages("plain", "version abc here");
    AiConversation unasked = withMessages("unasked", "a.c as well");

    Map<String, String> hits =
        conversations.firstMatchingContents(List.of(dotted.getId(), plain.getId()), "a.c");

    assertEquals(Map.of(dotted.getId(), "version a.c here"), hits);
    assertFalse(hits.containsKey(unasked.getId()));
  }

  @Test
  void shouldAnswerEmptyForNoConversations() {
    assertTrue(conversations.firstMatchingContents(List.of(), "x").isEmpty());
  }

  @Test
  void shouldGiveEveryRowOfAListItsOwnSnippet() throws Exception {
    withMessages("one", "first needle row");
    withMessages("two", "intro", "second needle row");
    withMessages("needle in the title only", "nothing relevant");
    withMessages("three", "third NEEDLE row");

    JsonNode items =
        json(call(get("/ai/conversations").param("q", "needle"), alice, null).andExpect(status().isOk()))
            .path("items");

    assertEquals(4, items.size());
    int withSnippet = 0;
    for (JsonNode item : items) {
      String title = item.path("title").asText();
      if (title.equals("needle in the title only")) {
        assertTrue(item.path("matchSnippet").isNull());
      } else {
        withSnippet++;
        assertTrue(
            item.path("matchSnippet").asText().toLowerCase().contains("needle row"),
            title + ": " + item.path("matchSnippet"));
      }
    }
    assertEquals(3, withSnippet);
  }
}
