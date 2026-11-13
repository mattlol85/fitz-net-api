package org.fitznet.fitznetapi.integration;

import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.LLAMA;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.VISION;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.names;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.png;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fitznet.fitznetapi.integration.AiConversationTestSupport.Sse;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.ModelTarget;
import org.fitznet.fitznetapi.service.ai.AttachmentService;
import org.fitznet.fitznetapi.service.ai.ConversationAccess;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * Deterministic stale-read races: a spy on {@link ConversationAccess} lets a "turn" land in Mongo
 * right after a writer loaded the conversation, which is exactly the window a whole-document save
 * used to lose. Also counts how often a turn reads its history and its images.
 */
class AiConversationStaleWriteIntegrationTest extends AiChatTestBase {

  @MockitoSpyBean private ConversationAccess access;
  @MockitoSpyBean private AttachmentService attachmentService;

  /** After the next {@code owned} call returns its (soon stale) copy, a turn "lands" in Mongo. */
  private void turnLandsAfterTheNextLoad(AiConversation conversation) {
    AtomicBoolean fired = new AtomicBoolean();
    doAnswer(
            invocation -> {
              Object loaded = invocation.callRealMethod();
              if (fired.compareAndSet(false, true)) {
                AiConversation fresh = conversations.findById(conversation.getId()).orElseThrow();
                fresh.setNextSeq(9);
                fresh.setMessageCount(8);
                fresh.setActiveLeafId("leaf-from-the-turn");
                fresh.setTarget(new ModelTarget("turn-model", nodeB));
                conversations.save(fresh);
              }
              return loaded;
            })
        .when(access)
        .owned(any(), any());
  }

  private void assertTurnFieldsSurvived(AiConversation conversation) {
    AiConversation after = conversations.findById(conversation.getId()).orElseThrow();
    assertEquals(9, after.getNextSeq());
    assertEquals(8, after.getMessageCount());
    assertEquals("leaf-from-the-turn", after.getActiveLeafId());
    assertEquals(new ModelTarget("turn-model", nodeB), after.getTarget());
  }

  @Test
  void shouldNotOverwriteATurnThatLandsBetweenAPatchLoadAndItsWrite() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    turnLandsAfterTheNextLoad(conversation);

    call(patch("/ai/conversations/" + conversation.getId()), alice, "{\"pinned\":true,\"title\":\"Renamed\"}")
        .andExpect(status().isOk());

    assertTurnFieldsSurvived(conversation);
    AiConversation after = conversations.findById(conversation.getId()).orElseThrow();
    assertTrue(after.isPinned());
    assertEquals("Renamed", after.getTitle());
    assertEquals("user", after.getTitleSource());
  }

  @Test
  void shouldOnlyTouchTheFieldsAPatchSends() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    AiConversation seeded = conversations.findById(conversation.getId()).orElseThrow();
    seeded.setSystemPrompt("Be terse");
    seeded.setPinned(true);
    conversations.save(seeded);
    turnLandsAfterTheNextLoad(conversation);

    call(patch("/ai/conversations/" + conversation.getId()), alice, "{\"title\":\"Only the title\"}")
        .andExpect(status().isOk());

    AiConversation after = conversations.findById(conversation.getId()).orElseThrow();
    assertEquals("Be terse", after.getSystemPrompt());
    assertTrue(after.isPinned());
    assertTurnFieldsSurvived(conversation);
  }

  @Test
  void shouldNotOverwriteATurnThatLandsWhileATitleIsBeingGenerated() throws Exception {
    alpha.completionReply("Generated title");
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "hello");
    turnLandsAfterTheNextLoad(conversation);

    call(post("/ai/conversations/" + conversation.getId() + "/title"), alice, null)
        .andExpect(status().isOk());

    assertTurnFieldsSurvived(conversation);
    AiConversation after = conversations.findById(conversation.getId()).orElseThrow();
    assertEquals("Generated title", after.getTitle());
    assertEquals("auto", after.getTitleSource());
  }

  // ---- history and images are read once per turn -------------------------------------------

  @Test
  void shouldReadTheHistoryOncePerTurn() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);

    send(conversation.getId(), "hello");

    verify(access, times(1)).history(conversation.getId());
  }

  @Test
  void shouldReadHistoryAndImagesOnceEvenWhenAutoFailsOver() throws Exception {
    alpha.chatStatus(500); // Alpha answers every chat with an error, before any chunk
    AiNode refusing = aiNodeRepository.findById(nodeA).orElseThrow();
    // Alpha reports more free VRAM, so Auto tries it first and then fails over to Bravo.
    refusing.setVramTotalMb(32768);
    refusing.setVramUsedMb(0);
    refusing.setVramReportedAt(Instant.now());
    aiNodeRepository.save(refusing);
    byte[] image = png(700);
    String imageId =
        json(
                call(
                        post("/ai/attachments"),
                        alice,
                        "{\"name\":\"a.png\",\"mimeType\":\"image/png\",\"dataBase64\":\"" + base64(image) + "\"}")
                    .andExpect(status().isOk()))
            .path("id")
            .asText();
    AiConversation conversation = conversation(VISION, null); // Auto

    List<Sse> events =
        sendJson(
            conversation.getId(),
            "{\"content\":\"look\",\"attachmentIds\":[\"" + imageId + "\"]}");

    assertEquals(2, events.stream().filter(e -> e.name().equals("meta")).count(), names(events).toString());
    assertEquals("done", events.get(events.size() - 1).name());
    JsonNode sent = sentBody(bravo, 0).path("messages").get(0);
    assertEquals(base64(image), sent.path("images").get(0).asText());
    verify(access, times(1)).history(conversation.getId());
    verify(attachmentService, times(1)).loadImages(anyCollection());
  }
}
