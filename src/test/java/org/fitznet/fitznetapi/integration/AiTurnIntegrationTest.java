package org.fitznet.fitznetapi.integration;

import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.LLAMA;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.MAPPER;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.QWEN;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.TINY;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.names;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.parse;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.seedConversation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.integration.AiConversationTestSupport.Sse;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.ModelTarget;
import org.fitznet.fitznetapi.repository.AiConversationRepository;
import org.fitznet.fitznetapi.repository.AiMessageRepository;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.repository.AiUserPrefsRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.ChatEventSink;
import org.fitznet.fitznetapi.service.ai.FakeOllama;
import org.fitznet.fitznetapi.service.ai.TurnService;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** The three streaming turn endpoints against fake Ollama nodes. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EmbeddedMongoTestConfiguration.class)
class AiTurnIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private UserRepository userRepository;
  @Autowired private AiNodeRepository aiNodeRepository;
  @Autowired private AiConversationRepository conversations;
  @Autowired private AiMessageRepository messages;
  @Autowired private AiUserPrefsRepository prefs;
  @Autowired private TurnService turnService;

  private FakeOllama alpha;
  private FakeOllama bravo;
  private String nodeA;
  private String nodeB;
  private String alice;
  private String bob;

  @BeforeEach
  void setUp() throws Exception {
    alpha = AiConversationTestSupport.withModels(new FakeOllama());
    bravo = AiConversationTestSupport.withModels(new FakeOllama());
    nodeA = AiConversationTestSupport.uniqueNodeId();
    nodeB = AiConversationTestSupport.uniqueNodeId();

    userRepository.deleteAll();
    conversations.deleteAll();
    messages.deleteAll();
    prefs.deleteAll();
    aiNodeRepository.deleteAll();
    alice = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "alice", "USER", "AI_USER");
    bob = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "bob", "USER", "AI_USER");
    aiNodeRepository.save(AiConversationTestSupport.node(nodeA, "Alpha", alpha.address()));
    aiNodeRepository.save(AiConversationTestSupport.node(nodeB, "Bravo", bravo.address()));
  }

  @AfterEach
  void tearDown() {
    alpha.close();
    bravo.close();
    conversations.deleteAll();
    messages.deleteAll();
    prefs.deleteAll();
    aiNodeRepository.deleteAll();
  }

  // ---- helpers -----------------------------------------------------------------------------

  private AiConversation conversation(String model, String nodeId) {
    return seedConversation(conversations, "alice", "New chat", new ModelTarget(model, nodeId));
  }

  private ResultActions call(MockHttpServletRequestBuilder builder, String token, String body)
      throws Exception {
    MockHttpServletRequestBuilder request = builder.contentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      request = request.header("Authorization", "Bearer " + token);
    }
    return mockMvc.perform(body == null ? request : request.content(body));
  }

  private String stream(MockHttpServletRequestBuilder builder, String body) throws Exception {
    return call(builder, alice, body)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private List<Sse> send(String conversationId, String content) throws Exception {
    return parse(
        stream(
            post("/ai/conversations/" + conversationId + "/messages"),
            "{\"content\":\"" + content + "\"}"));
  }

  private List<Sse> sendTo(String conversationId, String content, String model, String nodeId)
      throws Exception {
    return parse(
        stream(
            post("/ai/conversations/" + conversationId + "/messages"),
            "{\"content\":\""
                + content
                + "\",\"target\":{\"model\":\""
                + model
                + "\",\"nodeId\":"
                + (nodeId == null ? "null" : "\"" + nodeId + "\"")
                + "}}"));
  }

  private JsonNode detail(String conversationId) throws Exception {
    return MAPPER.readTree(
        call(get("/ai/conversations/" + conversationId), alice, null)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  /** The {@code messages} array of the n-th chat request a fake node received. */
  private static JsonNode sentMessages(FakeOllama ollama, int index) {
    return MAPPER.readTree(ollama.chatBodies().get(index)).path("messages");
  }

  private static JsonNode sentBody(FakeOllama ollama, int index) {
    return MAPPER.readTree(ollama.chatBodies().get(index));
  }

  private static List<String> roleAndContent(JsonNode wireMessages) {
    List<String> out = new ArrayList<>();
    for (JsonNode m : wireMessages) {
      out.add(m.path("role").asText() + ":" + m.path("content").asText());
    }
    return out;
  }

  private Sse only(List<Sse> events, String name) {
    List<Sse> found = events.stream().filter(e -> e.name().equals(name)).toList();
    assertEquals(1, found.size(), name + " in " + names(events));
    return found.get(0);
  }

  // ---- send and replay ---------------------------------------------------------------------

  @Test
  void shouldStreamMetaStatusTokensAndDoneWithIdsAndStats() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);

    call(post("/ai/conversations/" + conversation.getId() + "/messages"), alice, "{\"content\":\"hi\"}")
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", Matchers.startsWith("text/event-stream")))
        .andExpect(header().string("X-Accel-Buffering", "no"))
        .andExpect(header().string("Cache-Control", "no-cache"));
    List<Sse> events = send(conversation.getId(), "hello");

    assertEquals(
        List.of("meta", "status", "status", "token", "token", "done"), names(events));
    JsonNode meta = events.get(0).data();
    assertEquals(conversation.getId(), meta.path("conversationId").asText());
    assertTrue(meta.path("userMessageId").isString());
    assertTrue(meta.path("assistantMessageId").isString());
    assertEquals(nodeA, meta.path("nodeId").asText());
    assertEquals("Alpha", meta.path("nodeName").asText());
    assertEquals(LLAMA, meta.path("model").asText());
    assertFalse(meta.path("auto").asBoolean());
    assertTrue(meta.path("presetId").isNull());
    assertEquals(8192, meta.path("contextLength").asInt());
    assertTrue(meta.path("context").path("promptTokensEstimate").asInt() > 0);
    assertEquals(0, meta.path("context").path("trimmedCount").asInt());
    assertFalse(meta.path("context").path("summaryActive").asBoolean());
    assertEquals(0, meta.path("context").path("imagesOmitted").asInt());
    assertEquals("loading_model", events.get(1).data().path("phase").asText());
    assertEquals(nodeA, events.get(1).data().path("nodeId").asText());
    assertEquals("generating", events.get(2).data().path("phase").asText());
    assertEquals("Hello", events.get(3).data().path("content").asText());
    assertEquals(" there", events.get(4).data().path("content").asText());

    JsonNode done = events.get(5).data();
    assertEquals(meta.path("assistantMessageId").asText(), done.path("messageId").asText());
    assertEquals(meta.path("userMessageId").asText(), done.path("userMessageId").asText());
    assertEquals(10, done.path("promptTokens").asInt());
    assertEquals(2, done.path("completionTokens").asInt());
    assertEquals(8192, done.path("contextLength").asInt());
    assertEquals("stop", done.path("finishReason").asText());
    assertFalse(done.path("contextOverflow").asBoolean());
    JsonNode stats = done.path("stats");
    assertTrue(stats.path("ttftMs").asLong() >= 0);
    assertTrue(stats.path("totalMs").asLong() >= stats.path("ttftMs").asLong());
    assertEquals(0, stats.path("loadMs").asInt());
    assertTrue(stats.path("thinkingMs").isNull());
    assertEquals(2.0, stats.path("tokensPerSecond").asDouble());
  }

  @Test
  void shouldPersistBothMessagesAndReplayStoredHistoryOnTheNextSend() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);

    send(conversation.getId(), "My name is Bob");
    send(conversation.getId(), "Who am I?");

    // The browser sent only the new content; the server replayed the stored history.
    assertEquals(
        List.of("user:My name is Bob"), roleAndContent(sentMessages(alpha, 0)));
    assertEquals(
        List.of("user:My name is Bob", "assistant:Hello there", "user:Who am I?"),
        roleAndContent(sentMessages(alpha, 1)));
    JsonNode body = sentBody(alpha, 1);
    assertEquals(LLAMA, body.path("model").asText());
    assertTrue(body.path("stream").asBoolean());
    assertEquals("30m", body.path("keep_alive").asText());
    assertEquals(8192, body.path("options").path("num_ctx").asInt());
    assertTrue(body.path("think").isMissingNode());

    JsonNode detail = detail(conversation.getId());
    JsonNode stored = detail.path("messages");
    assertEquals(4, stored.size());
    assertEquals(4, detail.path("messageCount").asInt());
    for (int i = 0; i < 4; i++) {
      assertEquals(i + 1, stored.get(i).path("seq").asInt());
      assertEquals("complete", stored.get(i).path("status").asText());
      assertEquals(i % 2 == 0 ? "user" : "assistant", stored.get(i).path("role").asText());
    }
    assertTrue(stored.get(0).path("parentId").isNull());
    assertEquals(stored.get(0).path("id").asText(), stored.get(1).path("parentId").asText());
    assertEquals(stored.get(2).path("id").asText(), stored.get(3).path("parentId").asText());
    assertEquals("Hello there", stored.get(1).path("content").asText());
    assertTrue(stored.get(0).path("generation").isNull());
    JsonNode generation = stored.get(3).path("generation");
    assertEquals(nodeA, generation.path("nodeId").asText());
    assertEquals("Alpha", generation.path("nodeName").asText());
    assertEquals(LLAMA, generation.path("model").asText());
    assertFalse(generation.path("auto").asBoolean());
    assertEquals(8192, generation.path("contextLength").asInt());
    assertEquals(10, generation.path("promptTokens").asInt());
    assertEquals(2, generation.path("completionTokens").asInt());
    assertEquals("stop", generation.path("finishReason").asText());
    assertTrue(generation.path("totalMs").asLong() >= 0);
    assertEquals("My name is Bob", detail.path("title").asText());
    assertEquals("default", detail.path("titleSource").asText());
    assertTrue(detail.path("context").isObject());
  }

  @Test
  void shouldSetAProvisionalTitleFromTheFirstLineOfTheFirstMessageOnly() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);

    send(conversation.getId(), "First line\\nsecond line");
    send(conversation.getId(), "another question");

    JsonNode detail = detail(conversation.getId());
    assertEquals("First line", detail.path("title").asText());
    assertEquals("default", detail.path("titleSource").asText());

    AiConversation longTitle = conversation(LLAMA, nodeA);
    send(longTitle.getId(), "w".repeat(100));
    assertEquals(60, detail(longTitle.getId()).path("title").asText().length());
  }

  @Test
  void shouldRelayThinkingAndSendThinkTrueForAThinkingModel() throws Exception {
    alpha.chatLines(
        FakeOllama.thinkingChunk("Hmm"), FakeOllama.chunk("42"), FakeOllama.doneChunk(7, 3));
    AiConversation conversation = conversation(QWEN, nodeA);

    List<Sse> events = send(conversation.getId(), "meaning?");

    assertEquals(
        List.of("meta", "status", "status", "thinking", "token", "done"), names(events));
    assertEquals("Hmm", only(events, "thinking").data().path("content").asText());
    assertTrue(sentBody(alpha, 0).path("think").asBoolean());
    JsonNode assistant = detail(conversation.getId()).path("messages").get(1);
    assertEquals("Hmm", assistant.path("thinking").asText());
    assertEquals("42", assistant.path("content").asText());
    assertTrue(assistant.path("generation").path("thinkingMs").isNumber() || assistant.path("generation").path("thinkingMs").isNull());
  }

  @Test
  void shouldNeverReplayThinkingToTheModel() throws Exception {
    alpha.chatLines(
        FakeOllama.thinkingChunk("secret-reasoning"), FakeOllama.chunk("ok"), FakeOllama.doneChunk(7, 3));
    AiConversation conversation = conversation(QWEN, nodeA);

    send(conversation.getId(), "first");
    send(conversation.getId(), "second");

    assertFalse(alpha.chatBodies().get(1).contains("secret-reasoning"));
    assertEquals(
        List.of("user:first", "assistant:ok", "user:second"), roleAndContent(sentMessages(alpha, 1)));
  }

  @Test
  void shouldApplyTheConversationSystemPromptAsTheFirstMessage() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    conversation.setSystemPrompt("Be terse.");
    conversations.save(conversation);

    send(conversation.getId(), "hi");

    assertEquals(
        List.of("system:Be terse.", "user:hi"), roleAndContent(sentMessages(alpha, 0)));
  }

  @Test
  void shouldSkipErrorMessagesWhenReplayingHistory() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    AiConversationTestSupport.seedUser(conversations, messages, conversation, "q1");
    AiConversationTestSupport.seedMessage(
        conversations, messages, conversation, "assistant", "broken", "error");

    send(conversation.getId(), "q2");

    assertEquals(List.of("user:q1", "user:q2"), roleAndContent(sentMessages(alpha, 0)));
  }

  // ---- switching target --------------------------------------------------------------------

  @Test
  void shouldKeepHistoryAndRecordTheNewNodeAndModelWhenSwitchingBetweenTwoSends() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "first question");

    List<Sse> events = sendTo(conversation.getId(), "second question", QWEN, nodeB);

    assertEquals(nodeB, only(events, "meta").data().path("nodeId").asText());
    assertEquals(0, bravo.chatCalls.get() - 1, "exactly one chat reached Bravo");
    JsonNode body = sentBody(bravo, 0);
    assertEquals(QWEN, body.path("model").asText());
    assertEquals(
        List.of("user:first question", "assistant:Hello there", "user:second question"),
        roleAndContent(body.path("messages")));
    assertTrue(body.path("think").asBoolean(), "qwen reports the thinking capability");

    JsonNode detail = detail(conversation.getId());
    JsonNode first = detail.path("messages").get(1).path("generation");
    JsonNode second = detail.path("messages").get(3).path("generation");
    assertEquals(nodeA, first.path("nodeId").asText());
    assertEquals(LLAMA, first.path("model").asText());
    assertEquals(nodeB, second.path("nodeId").asText());
    assertEquals("Bravo", second.path("nodeName").asText());
    assertEquals(QWEN, second.path("model").asText());
    assertEquals(nodeB, detail.path("target").path("nodeId").asText());
    assertEquals(QWEN, detail.path("target").path("model").asText());
    ModelTarget last = prefs.findById("alice").orElseThrow().getLastTarget();
    assertEquals(new ModelTarget(QWEN, nodeB), last);
  }

  @Test
  void shouldUseTheConversationTargetWhenTheRequestCarriesNone() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeB);

    send(conversation.getId(), "hi");

    assertEquals(1, bravo.chatCalls.get());
    assertEquals(0, alpha.chatCalls.get());
  }

  // ---- regenerate and edit -----------------------------------------------------------------

  @Test
  void shouldRegenerateByReplacingTheAnswerAndAllowChangingTheTarget() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "question");
    JsonNode before = detail(conversation.getId()).path("messages");
    String userId = before.get(0).path("id").asText();
    String oldAssistantId = before.get(1).path("id").asText();
    bravo.chatLines(FakeOllama.chunk("Better"), FakeOllama.chunk(" answer"), FakeOllama.doneChunk(5, 2));

    List<Sse> events =
        parse(
            stream(
                post(
                    "/ai/conversations/"
                        + conversation.getId()
                        + "/messages/"
                        + oldAssistantId
                        + "/regenerate"),
                "{\"target\":{\"model\":\"" + LLAMA + "\",\"nodeId\":\"" + nodeB + "\"}}"));

    JsonNode meta = only(events, "meta").data();
    assertEquals(userId, meta.path("userMessageId").asText());
    assertNotEquals(oldAssistantId, meta.path("assistantMessageId").asText());
    assertEquals(nodeB, meta.path("nodeId").asText());
    assertEquals(List.of("user:question"), roleAndContent(sentMessages(bravo, 0)));

    JsonNode after = detail(conversation.getId());
    assertEquals(2, after.path("messages").size());
    assertEquals(userId, after.path("messages").get(0).path("id").asText());
    JsonNode assistant = after.path("messages").get(1);
    assertEquals("Better answer", assistant.path("content").asText());
    assertEquals(2, assistant.path("seq").asInt());
    assertEquals(userId, assistant.path("parentId").asText());
    assertEquals(nodeB, assistant.path("generation").path("nodeId").asText());
    assertEquals(nodeB, after.path("target").path("nodeId").asText());
    assertEquals(2, after.path("messageCount").asInt());
  }

  @Test
  void shouldRegenerateWithTheConversationTargetWhenTheBodyIsEmpty() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "question");
    String assistantId = detail(conversation.getId()).path("messages").get(1).path("id").asText();

    String sse =
        stream(
            post("/ai/conversations/" + conversation.getId() + "/messages/" + assistantId + "/regenerate"),
            null);

    assertEquals("done", names(parse(sse)).get(names(parse(sse)).size() - 1));
    assertEquals(2, alpha.chatCalls.get());
  }

  @Test
  void shouldDeleteTheRegeneratedMessageAndEverythingAfterIt() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "q1");
    send(conversation.getId(), "q2");
    JsonNode before = detail(conversation.getId()).path("messages");
    String firstAssistant = before.get(1).path("id").asText();

    stream(
        post("/ai/conversations/" + conversation.getId() + "/messages/" + firstAssistant + "/regenerate"),
        null);

    JsonNode after = detail(conversation.getId()).path("messages");
    assertEquals(2, after.size(), "the second exchange went with it");
    assertEquals("q1", after.get(0).path("content").asText());
    assertEquals(List.of("user:q1"), roleAndContent(sentMessages(alpha, 2)));
  }

  @Test
  void shouldEditInPlaceTruncateWhatFollowsAndAnswerAgain() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "original");
    send(conversation.getId(), "follow-up");
    JsonNode before = detail(conversation.getId()).path("messages");
    String userId = before.get(0).path("id").asText();
    assertTrue(before.get(0).path("editedAt").isNull());

    List<Sse> events =
        parse(
            stream(
                post("/ai/conversations/" + conversation.getId() + "/messages/" + userId + "/edit"),
                "{\"content\":\"changed\"}"));

    assertEquals("done", events.get(events.size() - 1).name());
    assertEquals(List.of("user:changed"), roleAndContent(sentMessages(alpha, 2)));
    JsonNode after = detail(conversation.getId());
    assertEquals(2, after.path("messages").size());
    JsonNode edited = after.path("messages").get(0);
    assertEquals(userId, edited.path("id").asText());
    assertEquals(1, edited.path("seq").asInt());
    assertEquals("changed", edited.path("content").asText());
    assertTrue(edited.path("editedAt").isString());
    assertEquals(2, after.path("messages").get(1).path("seq").asInt());
    assertEquals(2, after.path("messageCount").asInt());
    assertEquals(userId, only(events, "meta").data().path("userMessageId").asText());
  }

  @Test
  void shouldEditAMiddleMessageAndKeepWhatCameBefore() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "q1");
    send(conversation.getId(), "q2");
    String secondUser = detail(conversation.getId()).path("messages").get(2).path("id").asText();

    stream(
        post("/ai/conversations/" + conversation.getId() + "/messages/" + secondUser + "/edit"),
        "{\"content\":\"q2 edited\"}");

    assertEquals(
        List.of("user:q1", "assistant:Hello there", "user:q2 edited"),
        roleAndContent(sentMessages(alpha, 2)));
    assertEquals(4, detail(conversation.getId()).path("messages").size());
  }

  // ---- validation and concurrency ----------------------------------------------------------

  @Test
  void shouldRejectBadRequestsBeforeStreamingAndPersistNothing() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "question");
    JsonNode stored = detail(conversation.getId()).path("messages");
    String userId = stored.get(0).path("id").asText();
    String assistantId = stored.get(1).path("id").asText();
    long before = messages.count();
    String base = "/ai/conversations/" + conversation.getId();

    call(post(base + "/messages/" + userId + "/regenerate"), alice, null)
        .andExpect(status().isBadRequest());
    call(post(base + "/messages/" + assistantId + "/edit"), alice, "{\"content\":\"x\"}")
        .andExpect(status().isBadRequest());
    call(post(base + "/messages"), alice, "{\"content\":\"   \"}").andExpect(status().isBadRequest());
    call(post(base + "/messages"), alice, "{}").andExpect(status().isBadRequest());
    call(post(base + "/messages/" + userId + "/edit"), alice, "{\"content\":\"\"}")
        .andExpect(status().isBadRequest());
    call(post(base + "/messages"), alice, "{\"content\":\"" + "c".repeat(32001) + "\"}")
        .andExpect(status().isBadRequest());
    call(post(base + "/messages"), alice, "{\"content\":\"hi\",\"attachmentIds\":[\"abc\"]}")
        .andExpect(status().isBadRequest());
    call(post(base + "/messages"), alice, "{\"content\":\"hi\",\"target\":{\"model\":\"\"}}")
        .andExpect(status().isBadRequest());

    assertEquals(before, messages.count());
    assertEquals(1, alpha.chatCalls.get());
  }

  @Test
  void shouldRejectATurnWithNoTargetOnTheRequestOrTheConversation() throws Exception {
    AiConversation conversation = seedConversation(conversations, "alice", "c", null);

    call(post("/ai/conversations/" + conversation.getId() + "/messages"), alice, "{\"content\":\"hi\"}")
        .andExpect(status().isBadRequest());

    assertEquals(0, messages.count());
  }

  @Test
  void shouldAnswer404ForUnknownConversationsAndMessages() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "q");
    String base = "/ai/conversations/" + conversation.getId();

    call(post("/ai/conversations/000000000000000000000000/messages"), alice, "{\"content\":\"hi\"}")
        .andExpect(status().isNotFound());
    call(post(base + "/messages/000000000000000000000000/regenerate"), alice, null)
        .andExpect(status().isNotFound());
    call(post(base + "/messages/000000000000000000000000/edit"), alice, "{\"content\":\"x\"}")
        .andExpect(status().isNotFound());
    call(post(base + "/messages"), bob, "{\"content\":\"hi\"}").andExpect(status().isNotFound());
  }

  @Test
  void shouldAnswer409WhileAGenerationIsInFlightAndReleaseTheSlotAfterwards() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    alpha.holdChat();
    String base = "/ai/conversations/" + conversation.getId();
    Thread first =
        new Thread(
            () -> {
              try {
                stream(post(base + "/messages"), "{\"content\":\"slow\"}");
              } catch (Exception ex) {
                throw new IllegalStateException(ex);
              }
            });
    first.start();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (alpha.chatCalls.get() < 1 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(1, alpha.chatCalls.get());

    call(post(base + "/messages"), alice, "{\"content\":\"second\"}").andExpect(status().isConflict());
    call(delete(base), alice, null).andExpect(status().isConflict());
    call(post(base + "/summarize"), alice, null).andExpect(status().isConflict());
    JsonNode live = detail(conversation.getId()).path("messages");
    assertEquals("streaming", live.get(live.size() - 1).path("status").asText());

    alpha.releaseChat();
    first.join(10_000);
    send(conversation.getId(), "third"); // the slot was released
    assertEquals(4, detail(conversation.getId()).path("messages").size());
  }

  @Test
  void shouldReleaseTheSlotAfterAFailedValidation() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    String base = "/ai/conversations/" + conversation.getId();

    call(post(base + "/messages"), alice, "{\"content\":\"\"}").andExpect(status().isBadRequest());

    send(conversation.getId(), "fine");
  }

  // ---- routing errors ----------------------------------------------------------------------

  @Test
  void shouldAnswerNodeOfflineWithAlternativesAndStoreTheErrorMessage() throws Exception {
    AiNode stale = aiNodeRepository.findById(nodeA).orElseThrow();
    stale.setLastHeartbeatAt(Instant.now().minus(1, ChronoUnit.HOURS));
    aiNodeRepository.save(stale);
    AiConversation conversation = conversation(LLAMA, nodeA);

    List<Sse> events = send(conversation.getId(), "anyone there?");

    assertEquals(List.of("error"), names(events));
    JsonNode error = events.get(0).data();
    assertEquals("NODE_OFFLINE", error.path("code").asText());
    assertTrue(error.path("message").asText().contains("Alpha"));
    assertTrue(error.path("retryable").asBoolean());
    assertEquals(1, error.path("alternatives").size());
    JsonNode alternative = error.path("alternatives").get(0);
    assertEquals(nodeB, alternative.path("nodeId").asText());
    assertEquals("Bravo", alternative.path("nodeName").asText());
    assertEquals(LLAMA, alternative.path("model").asText());
    assertFalse(alternative.path("loaded").asBoolean());
    assertEquals("ONLINE", alternative.path("status").asText());

    JsonNode stored = detail(conversation.getId()).path("messages");
    assertEquals(2, stored.size());
    assertEquals("anyone there?", stored.get(0).path("content").asText());
    JsonNode assistant = stored.get(1);
    assertEquals("error", assistant.path("status").asText());
    assertEquals("NODE_OFFLINE", assistant.path("error").path("code").asText());
    assertEquals(assistant.path("id").asText(), error.path("messageId").asText());
    assertTrue(assistant.path("generation").isNull());
    assertEquals(0, alpha.chatCalls.get());
    assertEquals(0, bravo.chatCalls.get(), "an explicit node is never substituted silently");
  }

  @Test
  void shouldAnswerModelNotOnNodeWhenTheNodeLacksTheModel() throws Exception {
    AiConversation conversation = conversation("ghost:7b", nodeA);

    List<Sse> events = send(conversation.getId(), "hi");

    assertEquals("MODEL_NOT_ON_NODE", only(events, "error").data().path("code").asText());
    assertTrue(only(events, "error").data().has("alternatives"));
  }

  @Test
  void shouldAnswerNoNodeAvailableForAutoWhenNobodyHasTheModel() throws Exception {
    AiConversation conversation = conversation("ghost:7b", null);

    List<Sse> events = send(conversation.getId(), "hi");

    JsonNode error = only(events, "error").data();
    assertEquals("NO_NODE_AVAILABLE", error.path("code").asText());
    assertEquals(0, error.path("alternatives").size());
  }

  @Test
  void shouldAnswerContextTooSmallWhenTheNewestMessageAloneDoesNotFit() throws Exception {
    AiConversation conversation = conversation(TINY, nodeA); // 1024-token window

    List<Sse> events = send(conversation.getId(), "z".repeat(4000));

    assertEquals(List.of("error"), names(events));
    JsonNode error = events.get(0).data();
    assertEquals("CONTEXT_TOO_SMALL", error.path("code").asText());
    assertFalse(error.path("retryable").asBoolean());
    assertFalse(error.has("alternatives"));
    JsonNode stored = detail(conversation.getId()).path("messages");
    assertEquals(2, stored.size(), "the user message is kept");
    assertEquals("CONTEXT_TOO_SMALL", stored.get(1).path("error").path("code").asText());
    assertEquals(0, alpha.chatCalls.get());
  }

  // ---- Auto failover -----------------------------------------------------------------------

  @Test
  void shouldFailOverToTheNextNodeWhenTheFirstRefusesTheConnection() throws Exception {
    FakeOllama dead = new FakeOllama();
    String deadAddress = dead.address();
    dead.close();
    AiNode refusing = aiNodeRepository.findById(nodeA).orElseThrow();
    refusing.setAddress(deadAddress);
    // Alpha reports more free VRAM, so Auto prefers it even though its Ollama is unreachable.
    refusing.setVramTotalMb(32768);
    refusing.setVramUsedMb(0);
    refusing.setVramReportedAt(Instant.now());
    aiNodeRepository.save(refusing);
    AiConversation conversation = conversation(LLAMA, null); // Auto

    List<Sse> events = send(conversation.getId(), "hello");

    assertEquals(
        List.of("meta", "status", "status", "meta", "status", "status", "token", "token", "done"),
        names(events));
    JsonNode firstMeta = events.get(0).data();
    JsonNode failover = events.get(2).data();
    JsonNode secondMeta = events.get(3).data();
    assertEquals(nodeA, firstMeta.path("nodeId").asText());
    assertTrue(firstMeta.path("auto").asBoolean());
    assertEquals("failover", failover.path("phase").asText());
    assertEquals(nodeA, failover.path("fromNodeId").asText());
    assertEquals(nodeB, failover.path("toNodeId").asText());
    assertTrue(failover.path("message").asText().contains("Alpha"));
    assertTrue(failover.path("message").asText().contains("Bravo"));
    assertEquals(nodeB, secondMeta.path("nodeId").asText());
    assertEquals(firstMeta.path("conversationId").asText(), secondMeta.path("conversationId").asText());
    assertEquals(firstMeta.path("userMessageId").asText(), secondMeta.path("userMessageId").asText());
    assertEquals(
        firstMeta.path("assistantMessageId").asText(), secondMeta.path("assistantMessageId").asText());
    assertEquals(firstMeta.path("assistantMessageId").asText(), only(events, "done").data().path("messageId").asText());

    JsonNode assistant = detail(conversation.getId()).path("messages").get(1);
    assertEquals("complete", assistant.path("status").asText());
    assertEquals(nodeB, assistant.path("generation").path("nodeId").asText());
    assertTrue(assistant.path("generation").path("auto").asBoolean());
    assertEquals(1, bravo.chatCalls.get());
  }

  @Test
  void shouldEndWithTheErrorWhenEveryAutoCandidateRefuses() throws Exception {
    FakeOllama deadA = new FakeOllama();
    FakeOllama deadB = new FakeOllama();
    String addressA = deadA.address();
    String addressB = deadB.address();
    deadA.close();
    deadB.close();
    AiNode a = aiNodeRepository.findById(nodeA).orElseThrow();
    a.setAddress(addressA);
    aiNodeRepository.save(a);
    AiNode b = aiNodeRepository.findById(nodeB).orElseThrow();
    b.setAddress(addressB);
    aiNodeRepository.save(b);
    AiConversation conversation = conversation(LLAMA, null);

    List<Sse> events = send(conversation.getId(), "hello");

    JsonNode error = events.get(events.size() - 1).data();
    assertEquals("error", events.get(events.size() - 1).name());
    assertEquals("NODE_UNREACHABLE", error.path("code").asText());
    assertEquals(2, events.stream().filter(e -> e.name().equals("meta")).count());
    assertEquals(
        "error", detail(conversation.getId()).path("messages").get(1).path("status").asText());
  }

  @Test
  void shouldNotFailOverAfterTheFirstChunkAndListAlternatives() throws Exception {
    alpha.chatLines(FakeOllama.chunk("par")); // then the stream just ends: no done
    AiConversation conversation = conversation(LLAMA, null); // Auto, Alpha wins the name tie

    List<Sse> events = send(conversation.getId(), "hello");

    assertEquals(1, events.stream().filter(e -> e.name().equals("meta")).count());
    assertEquals(0, bravo.chatCalls.get());
    JsonNode error = only(events, "error").data();
    assertEquals("NODE_UNREACHABLE", error.path("code").asText());
    assertEquals(nodeB, error.path("alternatives").get(0).path("nodeId").asText());
    assertFalse(names(events).contains("done"));
    JsonNode assistant = detail(conversation.getId()).path("messages").get(1);
    assertEquals("error", assistant.path("status").asText());
    assertEquals("par", assistant.path("content").asText(), "the partial text is kept");
  }

  @Test
  void shouldNotFailOverForAnExplicitNode() throws Exception {
    alpha.chatStatus(500);
    AiConversation conversation = conversation(LLAMA, nodeA);

    List<Sse> events = send(conversation.getId(), "hello");

    assertEquals("NODE_ERROR", only(events, "error").data().path("code").asText());
    assertEquals(1, events.stream().filter(e -> e.name().equals("meta")).count());
    assertEquals(0, bravo.chatCalls.get());
  }

  // ---- cancel ------------------------------------------------------------------------------

  /** A sink whose client goes away after a few tokens. */
  private static final class CancellingSink implements ChatEventSink {
    private final int tokensBeforeCancel;
    private final AtomicInteger tokens = new AtomicInteger();
    final List<String> names = new ArrayList<>();
    private volatile boolean gone;

    CancellingSink(int tokensBeforeCancel) {
      this.tokensBeforeCancel = tokensBeforeCancel;
    }

    @Override
    public void event(String name, Map<String, ?> data) {
      if (gone) {
        throw new UncheckedIOException(new IOException("client closed"));
      }
      names.add(name);
      if (name.equals("token") && tokens.incrementAndGet() >= tokensBeforeCancel) {
        gone = true;
      }
    }

    @Override
    public void keepAlive() {
      if (gone) {
        throw new UncheckedIOException(new IOException("client closed"));
      }
    }
  }

  @Test
  void shouldPersistACancelledPartialAndDropTheUpstreamWhenTheClientGoesAway() throws Exception {
    alpha.chatForever();
    AiConversation conversation = conversation(LLAMA, nodeA);
    CancellingSink sink = new CancellingSink(3);

    TurnService.PreparedTurn turn =
        turnService.prepare(
            "alice",
            new TurnService.TurnCommand(
                TurnService.Kind.SEND,
                conversation.getId(),
                null,
                "go on forever",
                false,
                null,
                null,
                System.nanoTime()));
    turnService.run(turn, sink);

    assertTrue(alpha.chatDropped.await(5, TimeUnit.SECONDS), "Ollama connection was not dropped");
    assertFalse(sink.names.contains("done"));
    assertFalse(sink.names.contains("error"));
    JsonNode assistant = detail(conversation.getId()).path("messages").get(1);
    assertEquals("cancelled", assistant.path("status").asText());
    assertTrue(assistant.path("content").asText().startsWith("xxx"));
    assertEquals("cancelled", assistant.path("generation").path("finishReason").asText());
    assertTrue(assistant.path("error").isNull());
    send2xxAfterCancel(conversation);
  }

  private void send2xxAfterCancel(AiConversation conversation) throws Exception {
    // The slot was released: another turn can start (and is aborted straight away).
    TurnService.PreparedTurn next =
        turnService.prepare(
            "alice",
            new TurnService.TurnCommand(
                TurnService.Kind.SEND,
                conversation.getId(),
                null,
                "again",
                false,
                null,
                null,
                System.nanoTime()));
    turnService.abort(next);
    assertNotNull(next.assistantMessageId());
  }

  @Test
  void shouldStoreAnAbortedTurnAsInterrupted() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    TurnService.PreparedTurn turn =
        turnService.prepare(
            "alice",
            new TurnService.TurnCommand(
                TurnService.Kind.SEND,
                conversation.getId(),
                null,
                "hi",
                false,
                null,
                null,
                System.nanoTime()));

    turnService.abort(turn);

    JsonNode assistant = detail(conversation.getId()).path("messages").get(1);
    assertEquals("error", assistant.path("status").asText());
    assertEquals("INTERRUPTED", assistant.path("error").path("code").asText());
    assertNull(prefs.findById("alice").map(p -> p.getLastTarget()).orElse(null));
  }

  // ---- truncation and the summary ----------------------------------------------------------

  private AiConversation withSummary(int coversThroughSeq) throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "q1");
    send(conversation.getId(), "q2");
    AiConversation stored = conversations.findById(conversation.getId()).orElseThrow();
    stored.setSummary(
        new org.fitznet.fitznetapi.model.StoredSummary(
            "earlier", coversThroughSeq, 10, LLAMA, nodeA, false, Instant.now()));
    conversations.save(stored);
    return stored;
  }

  @Test
  void shouldKeepTheSummaryWhenARegenerateOnlyDeletesAfterTheSummarisedRange() throws Exception {
    AiConversation conversation = withSummary(2);
    String lastAssistant = detail(conversation.getId()).path("messages").get(3).path("id").asText();

    stream(
        post("/ai/conversations/" + conversation.getId() + "/messages/" + lastAssistant + "/regenerate"),
        null);

    assertNotNull(conversations.findById(conversation.getId()).orElseThrow().getSummary());
  }

  @Test
  void shouldDropTheSummaryWhenARegenerateReachesIntoTheSummarisedRange() throws Exception {
    AiConversation conversation = withSummary(4);
    String lastAssistant = detail(conversation.getId()).path("messages").get(3).path("id").asText();

    stream(
        post("/ai/conversations/" + conversation.getId() + "/messages/" + lastAssistant + "/regenerate"),
        null);

    assertNull(conversations.findById(conversation.getId()).orElseThrow().getSummary());
  }

  @Test
  void shouldDropTheSummaryWhenAnEditRewritesAMessageItCovers() throws Exception {
    AiConversation conversation = withSummary(2);
    String firstUser = detail(conversation.getId()).path("messages").get(0).path("id").asText();

    stream(
        post("/ai/conversations/" + conversation.getId() + "/messages/" + firstUser + "/edit"),
        "{\"content\":\"rewritten\"}");

    assertNull(conversations.findById(conversation.getId()).orElseThrow().getSummary());
  }

  @Test
  void shouldKeepTheSummaryOnAPlainSend() throws Exception {
    AiConversation conversation = withSummary(2);

    send(conversation.getId(), "q3");

    assertNotNull(conversations.findById(conversation.getId()).orElseThrow().getSummary());
  }

  // ---- titles ------------------------------------------------------------------------------

  @Test
  void shouldGenerateATitleFromTheNodeOfTheLastAnswerAndStoreItAsAuto() throws Exception {
    alpha.completionReply("Greeting the model");
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "hello there");

    call(post("/ai/conversations/" + conversation.getId() + "/title"), alice, null)
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.title").value("Greeting the model"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.titleSource").value("auto"));

    JsonNode request = sentBody(alpha, 1);
    assertFalse(request.path("stream").asBoolean());
    assertEquals(32, request.path("options").path("num_predict").asInt());
    assertEquals("system", request.path("messages").get(0).path("role").asText());
    assertTrue(request.path("think").isMissingNode(), "llama has no thinking capability");
    assertEquals(
        "Greeting the model",
        conversations.findById(conversation.getId()).orElseThrow().getTitle());
  }

  @Test
  void shouldTurnThinkingOffForATitleOnAThinkingModel() throws Exception {
    AiConversation conversation = conversation(QWEN, nodeA);
    send(conversation.getId(), "hello");

    call(post("/ai/conversations/" + conversation.getId() + "/title"), alice, null)
        .andExpect(status().isOk());

    JsonNode request = sentBody(alpha, 1);
    assertTrue(request.has("think"));
    assertFalse(request.path("think").asBoolean());
  }

  @Test
  void shouldLeaveAUserSetTitleUntouchedAndNotCallTheModel() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "hello");
    call(patch("/ai/conversations/" + conversation.getId()), alice, "{\"title\":\"Mine\"}")
        .andExpect(status().isOk());
    int callsBefore = alpha.chatCalls.get();

    call(post("/ai/conversations/" + conversation.getId() + "/title"), alice, null)
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.title").value("Mine"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.titleSource").value("user"));

    assertEquals(callsBefore, alpha.chatCalls.get());
  }

  @Test
  void shouldReturnTheConversationUnchangedWhenTheTitleCallFails() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "hello");
    alpha.completionStatus(500);

    call(post("/ai/conversations/" + conversation.getId() + "/title"), alice, null)
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.title").value("hello"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.titleSource").value("default"));
  }

  @Test
  void shouldReturnTheConversationUnchangedWhenTheTitleNodeIsGone() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);
    send(conversation.getId(), "hello");
    AiNode stale = aiNodeRepository.findById(nodeA).orElseThrow();
    stale.setLastHeartbeatAt(Instant.now().minus(1, ChronoUnit.HOURS));
    aiNodeRepository.save(stale);

    call(post("/ai/conversations/" + conversation.getId() + "/title"), alice, null)
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.titleSource").value("default"));
  }

  @Test
  void shouldLeaveAConversationWithoutAnswersUnchangedWhenAskedForATitle() throws Exception {
    AiConversation conversation = conversation(LLAMA, nodeA);

    call(post("/ai/conversations/" + conversation.getId() + "/title"), alice, null)
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.title").value("New chat"));

    assertEquals(0, alpha.chatCalls.get());
  }

  @Test
  void shouldStoreAnEmptyButFinishedGenerationAsComplete() throws Exception {
    alpha.chatLines(FakeOllama.doneChunk(3, 0));
    AiConversation conversation = conversation(LLAMA, nodeA);

    List<Sse> events = send(conversation.getId(), "silence");

    assertEquals("done", events.get(events.size() - 1).name());
    AiMessage assistant = messages.findByConversationIdOrderBySeqAsc(conversation.getId()).get(1);
    assertEquals("complete", assistant.getStatus());
    assertEquals("", assistant.getContent());
  }
}
