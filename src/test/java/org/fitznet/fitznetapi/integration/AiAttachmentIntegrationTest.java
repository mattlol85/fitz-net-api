package org.fitznet.fitznetapi.integration;

import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.LLAMA;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.VISION;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.jpeg;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.names;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.png;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.webp;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.fitznet.fitznetapi.integration.AiConversationTestSupport.Sse;
import org.fitznet.fitznetapi.model.AiAttachment;
import org.fitznet.fitznetapi.model.AiConversation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Image attachments: upload and download, validation, use in turns, edit and cascade deletes. */
class AiAttachmentIntegrationTest extends AiChatTestBase {

  private static final int MAX_BYTES = 3_145_728;

  private String uploadBody(String name, String mimeType, byte[] data) {
    return "{\"name\":\"" + name + "\",\"mimeType\":\"" + mimeType + "\",\"dataBase64\":\""
        + base64(data) + "\"}";
  }

  private String upload(String token, byte[] data) throws Exception {
    return json(
            call(post("/ai/attachments"), token, uploadBody("shot.png", "image/png", data))
                .andExpect(status().isOk()))
        .path("id")
        .asText();
  }

  private static String ids(String... ids) {
    return "[\"" + String.join("\",\"", ids) + "\"]";
  }

  private Sse only(List<Sse> events, String name) {
    List<Sse> found = events.stream().filter(e -> e.name().equals(name)).toList();
    assertEquals(1, found.size(), name + " in " + names(events));
    return found.get(0);
  }

  // ---- auth and ownership ------------------------------------------------------------------

  @Test
  void shouldRequireATokenAndAiAccess() throws Exception {
    call(post("/ai/attachments"), null, uploadBody("a.png", "image/png", png(100)))
        .andExpect(status().isUnauthorized());
    call(get("/ai/attachments/x"), null, null).andExpect(status().isUnauthorized());
    call(post("/ai/attachments"), plain, uploadBody("a.png", "image/png", png(100)))
        .andExpect(status().isForbidden());
    call(get("/ai/attachments/x"), plain, null).andExpect(status().isForbidden());
  }

  @Test
  void shouldAnswer404ForUnknownAndSomeoneElsesAttachment() throws Exception {
    String id = upload(alice, png(100));

    call(get("/ai/attachments/" + id), bob, null).andExpect(status().isNotFound());
    call(get("/ai/attachments/000000000000000000000000"), alice, null)
        .andExpect(status().isNotFound());
    call(get("/ai/attachments/" + id), admin, null).andExpect(status().isNotFound());
  }

  // ---- upload and download -----------------------------------------------------------------

  @Test
  void shouldRoundTripAnUploadWithTheRightHeaders() throws Exception {
    byte[] data = png(5000);

    call(post("/ai/attachments"), alice, uploadBody("bell.png", "image/png", data))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").isString())
        .andExpect(jsonPath("$.kind").value("image"))
        .andExpect(jsonPath("$.mimeType").value("image/png"))
        .andExpect(jsonPath("$.sizeBytes").value(5000))
        .andExpect(jsonPath("$.name").value("bell.png"));
    String id = attachments.findAll().get(0).getId();

    call(get("/ai/attachments/" + id), alice, null)
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"))
        .andExpect(content().bytes(data));
  }

  @Test
  void shouldServeEachTypeWithItsOwnContentType() throws Exception {
    String jpegId =
        json(call(post("/ai/attachments"), alice, uploadBody("a.jpg", "image/jpeg", jpeg(300)))
                .andExpect(status().isOk()))
            .path("id")
            .asText();
    String webpId =
        json(call(post("/ai/attachments"), alice, uploadBody("a.webp", "image/webp", webp(300)))
                .andExpect(status().isOk()))
            .path("id")
            .asText();

    call(get("/ai/attachments/" + jpegId), alice, null)
        .andExpect(header().string("Content-Type", "image/jpeg"));
    call(get("/ai/attachments/" + webpId), alice, null)
        .andExpect(header().string("Content-Type", "image/webp"));
  }

  @Test
  void shouldStoreAnUploadAsUnattachedWithAnExpiry() throws Exception {
    String id = upload(alice, png(100));

    AiAttachment stored = attachments.findById(id).orElseThrow();
    assertNull(stored.getConversationId());
    assertNull(stored.getMessageId());
    assertEquals("alice", stored.getOwnerUsername());
    assertNotNull(stored.getExpiresAt());
    Duration untilExpiry = Duration.between(Instant.now(), stored.getExpiresAt());
    assertTrue(untilExpiry.toHours() >= 23 && untilExpiry.toHours() <= 24, untilExpiry.toString());
  }

  // ---- validation --------------------------------------------------------------------------

  @Test
  void shouldRejectAnythingButPngJpegAndWebp() throws Exception {
    for (String type : new String[] {"image/gif", "image/svg+xml", "application/pdf", "text/plain"}) {
      call(post("/ai/attachments"), alice, uploadBody("a", type, png(100)))
          .andExpect(status().isBadRequest());
    }
    assertEquals(0, attachments.count());
  }

  @Test
  void shouldRejectBytesThatDoNotMatchTheDeclaredFormat() throws Exception {
    call(post("/ai/attachments"), alice, uploadBody("a.png", "image/png", jpeg(100)))
        .andExpect(status().isBadRequest());
    call(post("/ai/attachments"), alice, uploadBody("a.jpg", "image/jpeg", png(100)))
        .andExpect(status().isBadRequest());
    call(post("/ai/attachments"), alice, uploadBody("a.webp", "image/webp", png(100)))
        .andExpect(status().isBadRequest());
    byte[] riffButNotWebp = webp(100);
    riffButNotWebp[8] = 'A';
    call(post("/ai/attachments"), alice, uploadBody("a.webp", "image/webp", riffButNotWebp))
        .andExpect(status().isBadRequest());
    call(post("/ai/attachments"), alice, uploadBody("a.png", "image/png", "<html>".getBytes()))
        .andExpect(status().isBadRequest());
    assertEquals(0, attachments.count());
  }

  @Test
  void shouldAcceptAnImageExactlyAtTheSizeLimitAndRejectOneByteMore() throws Exception {
    call(post("/ai/attachments"), alice, uploadBody("big.png", "image/png", png(MAX_BYTES)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sizeBytes").value(MAX_BYTES));
    call(post("/ai/attachments"), alice, uploadBody("big.png", "image/png", png(MAX_BYTES + 1)))
        .andExpect(status().isBadRequest());
    assertEquals(1, attachments.count());
  }

  @Test
  void shouldRejectInvalidBase64AndMissingFields() throws Exception {
    call(post("/ai/attachments"), alice, "{\"name\":\"a\",\"mimeType\":\"image/png\",\"dataBase64\":\"not base64!!\"}")
        .andExpect(status().isBadRequest());
    call(post("/ai/attachments"), alice, "{\"name\":\"a\",\"mimeType\":\"image/png\",\"dataBase64\":\"\"}")
        .andExpect(status().isBadRequest());
    call(post("/ai/attachments"), alice, "{\"name\":\"a\",\"mimeType\":\"image/png\"}")
        .andExpect(status().isBadRequest());
    call(post("/ai/attachments"), alice, uploadBody("", "image/png", png(100)))
        .andExpect(status().isBadRequest());
    call(post("/ai/attachments"), alice, uploadBody("n".repeat(201), "image/png", png(100)))
        .andExpect(status().isBadRequest());
    call(post("/ai/attachments"), alice, "{\"name\":\"a\",\"dataBase64\":\"" + base64(png(100)) + "\"}")
        .andExpect(status().isBadRequest());
    assertEquals(0, attachments.count());
  }

  // ---- turns -------------------------------------------------------------------------------

  @Test
  void shouldSendTheImageAsBase64ToOllamaAndAttachItToTheUserMessage() throws Exception {
    byte[] data = png(2000);
    String id = upload(alice, data);
    AiConversation conversation = conversation(VISION, nodeA);

    List<Sse> events =
        sendJson(
            conversation.getId(),
            "{\"content\":\"what is this?\",\"attachmentIds\":" + ids(id) + "}");

    assertEquals("done", events.get(events.size() - 1).name());
    JsonNode sent = sentBody(alpha, 0).path("messages").get(0);
    assertEquals("what is this?", sent.path("content").asText());
    assertEquals(1, sent.path("images").size());
    assertEquals(base64(data), sent.path("images").get(0).asText());

    AiAttachment attached = attachments.findById(id).orElseThrow();
    assertEquals(conversation.getId(), attached.getConversationId());
    assertNotNull(attached.getMessageId());
    assertNull(attached.getExpiresAt(), "an attached upload no longer expires");
    JsonNode user = detail(conversation.getId()).path("messages").get(0);
    assertEquals(1, user.path("attachments").size());
    JsonNode ref = user.path("attachments").get(0);
    assertEquals(id, ref.path("id").asText());
    assertEquals("image", ref.path("kind").asText());
    assertEquals("image/png", ref.path("mimeType").asText());
    assertEquals(2000, ref.path("sizeBytes").asInt());
    assertEquals("shot.png", ref.path("name").asText());
    assertEquals(attached.getMessageId(), user.path("id").asText());
  }

  @Test
  void shouldAllowEmptyContentWhenThereIsAnImage() throws Exception {
    String id = upload(alice, png(500));
    AiConversation conversation = conversation(VISION, nodeA);

    List<Sse> events =
        sendJson(conversation.getId(), "{\"content\":\"\",\"attachmentIds\":" + ids(id) + "}");

    assertEquals("done", events.get(events.size() - 1).name());
    assertEquals("", sentBody(alpha, 0).path("messages").get(0).path("content").asText());
    assertEquals("New chat", detail(conversation.getId()).path("title").asText());

    String second = upload(alice, png(500));
    sendJson(conversation.getId(), "{\"attachmentIds\":" + ids(second) + "}");
    assertEquals(4, detail(conversation.getId()).path("messages").size());
  }

  @Test
  void shouldStillRequireContentWhenThereIsNoImage() throws Exception {
    AiConversation conversation = conversation(VISION, nodeA);

    call(
            post("/ai/conversations/" + conversation.getId() + "/messages"),
            alice,
            "{\"content\":\"\",\"attachmentIds\":[]}")
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldRejectUnknownForeignAndAlreadyAttachedIdsBeforeStreaming() throws Exception {
    String mine = upload(alice, png(100));
    String foreign = upload(bob, png(100));
    AiConversation conversation = conversation(VISION, nodeA);
    String path = "/ai/conversations/" + conversation.getId() + "/messages";

    call(post(path), alice, "{\"content\":\"x\",\"attachmentIds\":" + ids("000000000000000000000000") + "}")
        .andExpect(status().isBadRequest());
    call(post(path), alice, "{\"content\":\"x\",\"attachmentIds\":" + ids(foreign) + "}")
        .andExpect(status().isBadRequest());
    call(post(path), alice, "{\"content\":\"x\",\"attachmentIds\":" + ids(mine, mine) + "}")
        .andExpect(status().isBadRequest());
    assertEquals(0, messages.count(), "nothing is persisted on a 400");
    assertNull(attachments.findById(mine).orElseThrow().getMessageId());

    sendJson(conversation.getId(), "{\"content\":\"first\",\"attachmentIds\":" + ids(mine) + "}");
    call(post(path), alice, "{\"content\":\"again\",\"attachmentIds\":" + ids(mine) + "}")
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldRejectMoreAttachmentsThanTheLimit() throws Exception {
    String[] five = new String[5];
    for (int i = 0; i < five.length; i++) {
      five[i] = upload(alice, png(100));
    }
    AiConversation conversation = conversation(VISION, nodeA);

    call(
            post("/ai/conversations/" + conversation.getId() + "/messages"),
            alice,
            "{\"content\":\"x\",\"attachmentIds\":" + ids(five) + "}")
        .andExpect(status().isBadRequest());
    assertEquals(0, messages.count());

    String[] four = {five[0], five[1], five[2], five[3]};
    List<Sse> events =
        sendJson(
            conversation.getId(), "{\"content\":\"four\",\"attachmentIds\":" + ids(four) + "}");
    assertEquals("done", events.get(events.size() - 1).name());
    assertEquals(4, sentBody(alpha, 0).path("messages").get(0).path("images").size());
  }

  // ---- vision gating -----------------------------------------------------------------------

  @Test
  void shouldAnswerModelNotVisionAndStoreTheMessagesWithAMinimalGeneration() throws Exception {
    String id = upload(alice, png(300));
    AiConversation conversation = conversation(LLAMA, nodeA);

    List<Sse> events =
        sendJson(conversation.getId(), "{\"content\":\"see\",\"attachmentIds\":" + ids(id) + "}");

    assertEquals(List.of("error"), names(events));
    JsonNode error = events.get(0).data();
    assertEquals("MODEL_NOT_VISION", error.path("code").asText());
    assertFalse(error.path("retryable").asBoolean());
    assertFalse(error.has("alternatives"));
    assertEquals(0, alpha.chatCalls.get());

    JsonNode stored = detail(conversation.getId()).path("messages");
    assertEquals(2, stored.size());
    assertEquals("see", stored.get(0).path("content").asText());
    assertEquals(1, stored.get(0).path("attachments").size(), "the user message stays stored");
    assertEquals("error", stored.get(1).path("status").asText());
    assertEquals("MODEL_NOT_VISION", stored.get(1).path("error").path("code").asText());
    JsonNode generation = stored.get(1).path("generation");
    assertEquals(LLAMA, generation.path("model").asText());
    assertEquals(nodeA, generation.path("nodeId").asText());
    assertEquals("error", generation.path("finishReason").asText());
    assertNotNull(attachments.findById(id).orElseThrow().getMessageId());
  }

  @Test
  void shouldOmitOlderImagesForAModelWithoutVisionAndSendThemAgainForAVisionModel() throws Exception {
    byte[] data = png(400);
    String id = upload(alice, data);
    AiConversation conversation = conversation(VISION, nodeA);
    sendJson(conversation.getId(), "{\"content\":\"look\",\"attachmentIds\":" + ids(id) + "}");

    List<Sse> text =
        sendJson(
            conversation.getId(),
            "{\"content\":\"and now text\",\"target\":{\"model\":\"" + LLAMA + "\",\"nodeId\":\""
                + nodeA + "\"}}");

    JsonNode body = sentBody(alpha, 1);
    assertEquals(LLAMA, body.path("model").asText());
    assertTrue(body.path("messages").get(0).path("images").isMissingNode());
    assertEquals(1, only(text, "meta").data().path("context").path("imagesOmitted").asInt());

    List<Sse> again =
        sendJson(
            conversation.getId(),
            "{\"content\":\"back to vision\",\"target\":{\"model\":\"" + VISION + "\",\"nodeId\":\""
                + nodeA + "\"}}");

    JsonNode visionBody = sentBody(alpha, 2);
    assertEquals(base64(data), visionBody.path("messages").get(0).path("images").get(0).asText());
    assertEquals(0, only(again, "meta").data().path("context").path("imagesOmitted").asInt());
  }

  // ---- edit --------------------------------------------------------------------------------

  private String sendWithImage(AiConversation conversation, String text, String imageId)
      throws Exception {
    sendJson(
        conversation.getId(), "{\"content\":\"" + text + "\",\"attachmentIds\":" + ids(imageId) + "}");
    return detail(conversation.getId()).path("messages").get(0).path("id").asText();
  }

  @Test
  void shouldKeepTheAttachmentsOfAnEditThatSendsNoIds() throws Exception {
    byte[] data = png(300);
    String imageId = upload(alice, data);
    AiConversation conversation = conversation(VISION, nodeA);
    String userId = sendWithImage(conversation, "look", imageId);

    call(
            post("/ai/conversations/" + conversation.getId() + "/messages/" + userId + "/edit"),
            alice,
            "{\"content\":\"look again\"}")
        .andExpect(status().isOk());

    JsonNode sent = sentBody(alpha, 1).path("messages").get(0);
    assertEquals("look again", sent.path("content").asText());
    assertEquals(base64(data), sent.path("images").get(0).asText());
    assertTrue(attachments.findById(imageId).isPresent());
    assertEquals(1, detail(conversation.getId()).path("messages").get(0).path("attachments").size());
  }

  @Test
  void shouldAllowAnEditWithEmptyContentWhenItKeepsItsImage() throws Exception {
    String imageId = upload(alice, png(300));
    AiConversation conversation = conversation(VISION, nodeA);
    String userId = sendWithImage(conversation, "look", imageId);

    call(
            post("/ai/conversations/" + conversation.getId() + "/messages/" + userId + "/edit"),
            alice,
            "{\"content\":\"\"}")
        .andExpect(status().isOk());

    assertEquals("", sentBody(alpha, 1).path("messages").get(0).path("content").asText());
  }

  @Test
  void shouldReplaceTheAttachmentsOfAnEditThatSendsIdsAndDeleteTheRemovedOnes() throws Exception {
    String first = upload(alice, png(300));
    String second = upload(alice, png(310));
    AiConversation conversation = conversation(VISION, nodeA);
    String userId = sendWithImage(conversation, "look", first);
    String path = "/ai/conversations/" + conversation.getId() + "/messages/" + userId + "/edit";

    call(post(path), alice, "{\"content\":\"swap\",\"attachmentIds\":" + ids(second) + "}")
        .andExpect(status().isOk());

    assertTrue(attachments.findById(first).isEmpty(), "the removed image is deleted");
    AiAttachment kept = attachments.findById(second).orElseThrow();
    assertEquals(userId, kept.getMessageId());
    assertNull(kept.getExpiresAt());
    JsonNode user = detail(conversation.getId()).path("messages").get(0);
    assertEquals(1, user.path("attachments").size());
    assertEquals(second, user.path("attachments").get(0).path("id").asText());
    assertEquals(1, sentBody(alpha, 1).path("messages").get(0).path("images").size());
  }

  @Test
  void shouldKeepAnImageNamedAgainInAnEditAndAddANewOne() throws Exception {
    String first = upload(alice, png(300));
    String added = upload(alice, png(310));
    AiConversation conversation = conversation(VISION, nodeA);
    String userId = sendWithImage(conversation, "look", first);

    call(
            post("/ai/conversations/" + conversation.getId() + "/messages/" + userId + "/edit"),
            alice,
            "{\"content\":\"both\",\"attachmentIds\":" + ids(first, added) + "}")
        .andExpect(status().isOk());

    assertTrue(attachments.findById(first).isPresent());
    assertEquals(userId, attachments.findById(added).orElseThrow().getMessageId());
    assertEquals(2, sentBody(alpha, 1).path("messages").get(0).path("images").size());
  }

  @Test
  void shouldRemoveAllImagesWhenAnEditSendsAnEmptyIdList() throws Exception {
    String imageId = upload(alice, png(300));
    AiConversation conversation = conversation(VISION, nodeA);
    String userId = sendWithImage(conversation, "look", imageId);
    String path = "/ai/conversations/" + conversation.getId() + "/messages/" + userId + "/edit";

    call(post(path), alice, "{\"content\":\"\",\"attachmentIds\":[]}")
        .andExpect(status().isBadRequest());
    assertTrue(attachments.findById(imageId).isPresent(), "a rejected edit changes nothing");

    call(post(path), alice, "{\"content\":\"text only\",\"attachmentIds\":[]}")
        .andExpect(status().isOk());

    assertTrue(attachments.findById(imageId).isEmpty());
    assertEquals(0, detail(conversation.getId()).path("messages").get(0).path("attachments").size());
    assertTrue(sentBody(alpha, 1).path("messages").get(0).path("images").isMissingNode());
  }

  // ---- cascade -----------------------------------------------------------------------------

  @Test
  void shouldDeleteTheAttachmentsOfMessagesThatARegenerateTruncates() throws Exception {
    String imageId = upload(alice, png(300));
    AiConversation conversation = conversation(VISION, nodeA);
    send(conversation.getId(), "first");
    sendJson(conversation.getId(), "{\"content\":\"second\",\"attachmentIds\":" + ids(imageId) + "}");
    String firstAssistant = detail(conversation.getId()).path("messages").get(1).path("id").asText();

    call(
            post("/ai/conversations/" + conversation.getId() + "/messages/" + firstAssistant + "/regenerate"),
            alice,
            null)
        .andExpect(status().isOk());

    assertTrue(attachments.findById(imageId).isEmpty());
  }

  @Test
  void shouldDeleteTheAttachmentsOfMessagesThatAnEditTruncates() throws Exception {
    String imageId = upload(alice, png(300));
    AiConversation conversation = conversation(VISION, nodeA);
    send(conversation.getId(), "first");
    sendJson(conversation.getId(), "{\"content\":\"second\",\"attachmentIds\":" + ids(imageId) + "}");
    String firstUser = detail(conversation.getId()).path("messages").get(0).path("id").asText();

    call(
            post("/ai/conversations/" + conversation.getId() + "/messages/" + firstUser + "/edit"),
            alice,
            "{\"content\":\"rewritten\"}")
        .andExpect(status().isOk());

    assertTrue(attachments.findById(imageId).isEmpty());
  }

  @Test
  void shouldDeleteTheAttachmentsOfADeletedConversationButNotUnusedUploads() throws Exception {
    String attached = upload(alice, png(300));
    String unused = upload(alice, png(310));
    AiConversation conversation = conversation(VISION, nodeA);
    sendWithImage(conversation, "look", attached);

    call(delete("/ai/conversations/" + conversation.getId()), alice, null)
        .andExpect(status().isOk());

    assertTrue(attachments.findById(attached).isEmpty());
    assertTrue(attachments.findById(unused).isPresent());
    assertEquals(1, attachments.count());
  }

  @Test
  void shouldKeepTheImageOfAnEarlierMessageWhenALaterOneIsRegenerated() throws Exception {
    String imageId = upload(alice, png(300));
    AiConversation conversation = conversation(VISION, nodeA);
    sendWithImage(conversation, "look", imageId);
    send(conversation.getId(), "follow-up");
    String lastAssistant = detail(conversation.getId()).path("messages").get(3).path("id").asText();

    call(
            post("/ai/conversations/" + conversation.getId() + "/messages/" + lastAssistant + "/regenerate"),
            alice,
            null)
        .andExpect(status().isOk());

    assertTrue(attachments.findById(imageId).isPresent());
    assertArrayEquals(
        png(300), attachments.findById(imageId).orElseThrow().getData());
    assertNotNull(attachments.findById(imageId).orElseThrow().getMessageId());
  }
}
