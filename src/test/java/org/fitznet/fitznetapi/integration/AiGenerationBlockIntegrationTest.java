package org.fitznet.fitznetapi.integration;

import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.LLAMA;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.TINY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fitznet.fitznetapi.integration.AiConversationTestSupport.Sse;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.service.ai.FakeOllama;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The {@code generation} block: the stored overflow flag and the minimal block on early failures. */
class AiGenerationBlockIntegrationTest extends AiChatTestBase {

  private JsonNode assistant(AiConversation conversation) throws Exception {
    JsonNode stored = detail(conversation.getId()).path("messages");
    return stored.get(stored.size() - 1);
  }

  private static void assertMinimal(JsonNode generation, String model, String nodeId, String nodeName, boolean auto) {
    assertEquals(model, generation.path("model").asText());
    if (nodeId == null) {
      assertTrue(generation.path("nodeId").isNull());
    } else {
      assertEquals(nodeId, generation.path("nodeId").asText());
    }
    if (nodeName == null) {
      assertTrue(generation.path("nodeName").isNull());
    } else {
      assertEquals(nodeName, generation.path("nodeName").asText());
    }
    assertEquals(auto, generation.path("auto").asBoolean());
    assertEquals("error", generation.path("finishReason").asText());
    for (String nullField :
        new String[] {
          "presetId", "presetName", "contextLength", "promptTokens", "completionTokens", "ttftMs",
          "totalMs", "loadMs", "thinkingMs", "tokensPerSecond", "contextOverflow"
        }) {
      assertTrue(generation.path(nullField).isNull(), nullField + " should be null");
    }
  }

  // ---- contextOverflow ---------------------------------------------------------------------

  @Test
  void shouldStoreAndReturnContextOverflowTrueWhenThePromptFilledTheWindow() throws Exception {
    alpha.chatLines(FakeOllama.chunk("cut"), FakeOllama.doneChunk(8192, 5));
    AiConversation conversation = conversation(LLAMA, nodeA);

    List<Sse> events = send(conversation.getId(), "long prompt");

    JsonNode done = events.get(events.size() - 1).data();
    assertEquals("done", events.get(events.size() - 1).name());
    assertTrue(done.path("contextOverflow").asBoolean());
    assertTrue(assistant(conversation).path("generation").path("contextOverflow").asBoolean());
    assertEquals(
        true,
        messages
            .findByConversationIdOrderBySeqAsc(conversation.getId())
            .get(1)
            .getGeneration()
            .contextOverflow());
  }

  @Test
  void shouldStoreContextOverflowFalseForANormalTurn() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);

    List<Sse> events = send(conversation.getId(), "short");

    assertFalse(events.get(events.size() - 1).data().path("contextOverflow").asBoolean());
    JsonNode generation = assistant(conversation).path("generation");
    assertTrue(generation.path("contextOverflow").isBoolean());
    assertFalse(generation.path("contextOverflow").asBoolean());
  }

  @Test
  void shouldLeaveContextOverflowNullWhenTheTurnDidNotComplete() throws Exception {
    alpha.chatLines(FakeOllama.chunk("par")); // the stream ends without a final chunk
    AiConversation conversation = conversation(LLAMA, nodeA);

    send(conversation.getId(), "hello");

    JsonNode generation = assistant(conversation).path("generation");
    assertEquals("error", generation.path("finishReason").asText());
    assertEquals(nodeA, generation.path("nodeId").asText());
    assertTrue(generation.path("contextOverflow").isNull());
  }

  // ---- minimal generation on early failures ------------------------------------------------

  @Test
  void shouldStoreTheRequestedModelForAutoWhenNoNodeCanServeIt() throws Exception {
    AiConversation conversation = conversation("ghost:7b", null);

    List<Sse> events = send(conversation.getId(), "hi");

    assertEquals("NO_NODE_AVAILABLE", events.get(0).data().path("code").asText());
    assertMinimal(assistant(conversation).path("generation"), "ghost:7b", null, null, true);
  }

  @Test
  void shouldStoreTheRequestedNodeWithItsNameWhenTheNodeLacksTheModel() throws Exception {
    AiConversation conversation = conversation("ghost:7b", nodeA);

    send(conversation.getId(), "hi");

    assertMinimal(assistant(conversation).path("generation"), "ghost:7b", nodeA, "Alpha", false);
  }

  @Test
  void shouldStoreTheRequestedNodeIdWithoutANameWhenTheNodeIsUnknown() throws Exception {
    AiConversation conversation = conversation(LLAMA, "no-such-node");

    List<Sse> events = send(conversation.getId(), "hi");

    assertEquals("NODE_OFFLINE", events.get(0).data().path("code").asText());
    assertMinimal(assistant(conversation).path("generation"), LLAMA, "no-such-node", null, false);
  }

  @Test
  void shouldStoreAMinimalBlockWhenTheContextIsTooSmall() throws Exception {
    AiConversation conversation = conversation(TINY, null);

    List<Sse> events = send(conversation.getId(), "z".repeat(4000));

    assertEquals("CONTEXT_TOO_SMALL", events.get(0).data().path("code").asText());
    assertMinimal(assistant(conversation).path("generation"), TINY, null, null, true);
  }

  @Test
  void shouldGiveTheClientEverythingItNeedsToOfferAlternativesAfterAReload() throws Exception {
    AiConversation conversation = conversation("ghost:7b", nodeA);
    send(conversation.getId(), "hi");

    JsonNode generation = assistant(conversation).path("generation");

    assertEquals("ghost:7b", generation.path("model").asText());
    assertEquals(nodeA, generation.path("nodeId").asText());
  }
}
