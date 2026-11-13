package org.fitznet.fitznetapi.integration;

import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.LLAMA;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.QWEN;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.TINY;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.names;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.seedAssistant;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.seedUser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.fitznet.fitznetapi.integration.AiConversationTestSupport.Sse;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiPreset;
import org.fitznet.fitznetapi.model.ModelTarget;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Presets wired into conversations: generation, create/PATCH, and the context preflight. */
class AiPresetTurnIntegrationTest extends AiChatTestBase {

  private AiConversation withPreset(AiPreset preset, String systemPromptOverride) {
    AiConversation conversation = conversation(LLAMA, nodeA);
    conversation.setPresetId(preset.getId());
    conversation.setSystemPrompt(systemPromptOverride);
    return conversations.save(conversation);
  }

  private static List<String> roles(JsonNode wire) {
    List<String> out = new java.util.ArrayList<>();
    for (JsonNode m : wire) {
      out.add(m.path("role").asText() + ":" + m.path("content").asText());
    }
    return out;
  }

  // ---- generation --------------------------------------------------------------------------

  @Test
  void shouldSendThePresetSystemPromptTemperatureAndNumCtx() throws Exception {
    AiPreset preset = savePreset("alice", "Pirate", null, null, "Talk like a pirate.", 0.3, 2048);
    AiConversation conversation = withPreset(preset, null);

    List<Sse> events = send(conversation.getId(), "hello");

    JsonNode body = sentBody(alpha, 0);
    assertEquals(
        List.of("system:Talk like a pirate.", "user:hello"), roles(body.path("messages")));
    assertEquals(0.3, body.path("options").path("temperature").asDouble());
    assertEquals(2048, body.path("options").path("num_ctx").asInt());
    JsonNode meta = events.get(0).data();
    assertEquals(preset.getId(), meta.path("presetId").asText());
    assertEquals(2048, meta.path("contextLength").asInt());
    assertEquals("done", events.get(events.size() - 1).name());
    assertEquals(2048, events.get(events.size() - 1).data().path("contextLength").asInt());
  }

  @Test
  void shouldRecordPresetIdAndNameInTheGenerationBlock() throws Exception {
    AiPreset preset = savePreset("alice", "Pirate", null, null, "Arr.", null, null);
    AiConversation conversation = withPreset(preset, null);

    send(conversation.getId(), "hello");

    JsonNode generation = detail(conversation.getId()).path("messages").get(1).path("generation");
    assertEquals(preset.getId(), generation.path("presetId").asText());
    assertEquals("Pirate", generation.path("presetName").asText());
  }

  @Test
  void shouldLetTheConversationSystemPromptOverrideThePresets() throws Exception {
    AiPreset preset = savePreset("alice", "Pirate", null, null, "Preset prompt", null, null);
    AiConversation conversation = withPreset(preset, "Conversation prompt");

    send(conversation.getId(), "hello");

    assertEquals(
        List.of("system:Conversation prompt", "user:hello"),
        roles(sentBody(alpha, 0).path("messages")));
  }

  @Test
  void shouldLeaveTemperatureAndWindowAloneWhenThePresetSetsNone() throws Exception {
    AiPreset preset = savePreset("alice", "Plain", null, null, null, null, null);
    AiConversation conversation = withPreset(preset, null);

    send(conversation.getId(), "hello");

    JsonNode body = sentBody(alpha, 0);
    assertTrue(body.path("options").path("temperature").isMissingNode());
    assertEquals(8192, body.path("options").path("num_ctx").asInt());
    assertEquals(List.of("user:hello"), roles(body.path("messages")));
  }

  @Test
  void shouldReadThePresetAtGenerationTimeNotAtSelectionTime() throws Exception {
    AiPreset preset = savePreset("alice", "Pirate", null, null, "First prompt", null, null);
    AiConversation conversation = withPreset(preset, null);
    send(conversation.getId(), "one");

    preset.setSystemPrompt("Second prompt");
    preset.setName("Renamed");
    presets.save(preset);
    send(conversation.getId(), "two");

    assertEquals("system:First prompt", roles(sentBody(alpha, 0).path("messages")).get(0));
    assertEquals("system:Second prompt", roles(sentBody(alpha, 1).path("messages")).get(0));
    JsonNode second = detail(conversation.getId()).path("messages").get(3).path("generation");
    assertEquals("Renamed", second.path("presetName").asText());
  }

  @Test
  void shouldIgnoreADeletedPresetInsteadOfFailingTheTurn() throws Exception {
    AiPreset preset = savePreset("alice", "Gone", null, null, "Prompt", 0.9, 1024);
    AiConversation conversation = withPreset(preset, null);
    presets.deleteById(preset.getId());

    List<Sse> events = send(conversation.getId(), "hello");

    assertEquals("done", events.get(events.size() - 1).name());
    JsonNode body = sentBody(alpha, 0);
    assertEquals(List.of("user:hello"), roles(body.path("messages")));
    assertEquals(8192, body.path("options").path("num_ctx").asInt());
    assertTrue(events.get(0).data().path("presetId").isNull());
    JsonNode generation = detail(conversation.getId()).path("messages").get(1).path("generation");
    assertTrue(generation.path("presetId").isNull());
    assertTrue(generation.path("presetName").isNull());
  }

  @Test
  void shouldIgnoreAPresetThatIsNoLongerVisibleToTheOwner() throws Exception {
    AiPreset others = savePreset("bob", "Bobs", null, null, "Bob prompt", null, null);
    AiConversation conversation = withPreset(others, null);

    List<Sse> events = send(conversation.getId(), "hello");

    assertEquals("done", events.get(events.size() - 1).name());
    assertEquals(List.of("user:hello"), roles(sentBody(alpha, 0).path("messages")));
  }

  // ---- create and PATCH --------------------------------------------------------------------

  @Test
  void shouldRejectAnUnknownOrInvisiblePresetIdOnCreateAndPatch() throws Exception {
    AiPreset bobs = savePreset("bob", "Bobs", null, null, null, null, null);
    AiConversation conversation = conversation(LLAMA, nodeA);

    call(post("/ai/conversations"), alice, "{\"settings\":{\"presetId\":\"000000000000000000000000\"}}")
        .andExpect(status().isBadRequest());
    call(post("/ai/conversations"), alice, "{\"settings\":{\"presetId\":\"" + bobs.getId() + "\"}}")
        .andExpect(status().isBadRequest());
    call(
            patch("/ai/conversations/" + conversation.getId()),
            alice,
            "{\"settings\":{\"presetId\":\"000000000000000000000000\"}}")
        .andExpect(status().isBadRequest());
    call(
            patch("/ai/conversations/" + conversation.getId()),
            alice,
            "{\"settings\":{\"presetId\":\"" + bobs.getId() + "\"}}")
        .andExpect(status().isBadRequest());

    assertEquals(1, conversations.count());
    assertEquals(null, conversations.findById(conversation.getId()).orElseThrow().getPresetId());
  }

  @Test
  void shouldAcceptASharedPresetTheCallerMaySee() throws Exception {
    AiPreset shared =
        presets.save(
            AiPreset.builder()
                .ownerUsername("admin")
                .name("Shared")
                .visibility(AiPreset.SHARED)
                .requiredPermission("RADARR")
                .build());

    call(post("/ai/conversations"), radarr, "{\"settings\":{\"presetId\":\"" + shared.getId() + "\"}}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settings.presetId").value(shared.getId()));
    call(post("/ai/conversations"), alice, "{\"settings\":{\"presetId\":\"" + shared.getId() + "\"}}")
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldCopyThePinnedModelIntoTheTargetOnlyWhenNoTargetIsSent() throws Exception {
    AiPreset pinned = savePreset("alice", "Pinned", QWEN, nodeB, null, null, null);

    call(post("/ai/conversations"), alice, "{\"settings\":{\"presetId\":\"" + pinned.getId() + "\"}}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.target.model").value(QWEN))
        .andExpect(jsonPath("$.target.nodeId").value(nodeB));
    call(
            post("/ai/conversations"),
            alice,
            "{\"target\":{\"model\":\"" + LLAMA + "\"},\"settings\":{\"presetId\":\"" + pinned.getId() + "\"}}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.target.model").value(LLAMA))
        .andExpect(jsonPath("$.target.nodeId").isEmpty());
  }

  @Test
  void shouldCopyThePinnedModelOnPatchOnlyWhenNoTargetIsInTheSameRequest() throws Exception {
    AiPreset pinned = savePreset("alice", "Pinned", QWEN, null, null, null, null);
    AiConversation first = conversation(LLAMA, nodeA);
    AiConversation second = conversation(LLAMA, nodeA);

    call(
            patch("/ai/conversations/" + first.getId()),
            alice,
            "{\"settings\":{\"presetId\":\"" + pinned.getId() + "\"}}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.target.model").value(QWEN))
        .andExpect(jsonPath("$.target.nodeId").isEmpty());
    call(
            patch("/ai/conversations/" + second.getId()),
            alice,
            "{\"target\":{\"model\":\"" + TINY + "\"},\"settings\":{\"presetId\":\"" + pinned.getId() + "\"}}")
        .andExpect(jsonPath("$.target.model").value(TINY));

    assertEquals(new ModelTarget(TINY, null), prefs.findById("alice").orElseThrow().getLastTarget());
  }

  @Test
  void shouldLeaveTheTargetAloneWhenThePresetPinsNoModel() throws Exception {
    AiPreset loose = savePreset("alice", "Loose", null, null, "x", null, null);
    AiConversation conversation = conversation(LLAMA, nodeA);

    call(
            patch("/ai/conversations/" + conversation.getId()),
            alice,
            "{\"settings\":{\"presetId\":\"" + loose.getId() + "\"}}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.target.model").value(LLAMA))
        .andExpect(jsonPath("$.target.nodeId").value(nodeA))
        .andExpect(jsonPath("$.settings.presetId").value(loose.getId()));
  }

  // ---- context -----------------------------------------------------------------------------

  @Test
  void shouldReflectThePresetNumCtxInThePreflightAndTheDetail() throws Exception {
    AiPreset small = savePreset("alice", "Small", null, null, null, null, 1024);
    AiConversation conversation = conversation(LLAMA, nodeA);
    for (int i = 0; i < 7; i++) {
      if (i % 2 == 0) {
        seedUser(conversations, messages, conversation, String.valueOf((char) ('a' + i)).repeat(1500));
      } else {
        seedAssistant(conversations, messages, conversation, String.valueOf((char) ('a' + i)).repeat(1500));
      }
    }
    String path = "/ai/conversations/" + conversation.getId();

    call(get(path + "/context"), alice, null)
        .andExpect(jsonPath("$.contextLength").value(8192))
        .andExpect(jsonPath("$.trimmedCount").value(0));

    AiConversation stored = conversations.findById(conversation.getId()).orElseThrow();
    stored.setPresetId(small.getId());
    conversations.save(stored);

    call(get(path + "/context"), alice, null)
        .andExpect(jsonPath("$.contextLength").value(1024))
        .andExpect(jsonPath("$.trimmedCount").value(6))
        .andExpect(jsonPath("$.fits").value(false));
    call(get(path), alice, null)
        .andExpect(jsonPath("$.context.contextLength").value(1024))
        .andExpect(jsonPath("$.context.trimmedCount").value(6));
  }

  @Test
  void shouldUseThePresetWindowForARealTurnAsWellAsThePreflight() throws Exception {
    AiPreset small = savePreset("alice", "Small", null, null, null, null, 1024);
    AiConversation conversation = conversation(LLAMA, nodeA);
    conversation.setPresetId(small.getId());
    conversations.save(conversation);
    for (int i = 0; i < 7; i++) {
      seedUser(conversations, messages, conversation, String.valueOf((char) ('a' + i)).repeat(400));
    }

    List<Sse> events = send(conversation.getId(), "q");

    JsonNode meta = events.get(0).data();
    assertEquals(1024, meta.path("contextLength").asInt());
    assertTrue(meta.path("context").path("trimmedCount").asInt() > 0);
    assertEquals(List.of("meta", "status", "status", "token", "token", "done"), names(events));
  }
}
