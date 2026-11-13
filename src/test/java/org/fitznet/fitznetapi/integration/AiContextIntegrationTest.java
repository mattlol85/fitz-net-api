package org.fitznet.fitznetapi.integration;

import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.LLAMA;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.MAPPER;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.TINY;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.parse;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.seedAssistant;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.seedConversation;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.seedUser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.ModelTarget;
import org.fitznet.fitznetapi.model.StoredSummary;
import org.fitznet.fitznetapi.repository.AiConversationRepository;
import org.fitznet.fitznetapi.repository.AiMessageRepository;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.repository.AiUserPrefsRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.ai.FakeOllama;
import org.fitznet.fitznetapi.util.JwtUtil;
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

/** Context window: preflight usage, trimming as seen by turns, and "Summarise older turns". */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EmbeddedMongoTestConfiguration.class)
class AiContextIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private UserRepository userRepository;
  @Autowired private AiNodeRepository aiNodeRepository;
  @Autowired private AiConversationRepository conversations;
  @Autowired private AiMessageRepository messages;
  @Autowired private AiUserPrefsRepository prefs;

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

  private ResultActions call(MockHttpServletRequestBuilder builder, String token, String body)
      throws Exception {
    MockHttpServletRequestBuilder request = builder.contentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      request = request.header("Authorization", "Bearer " + token);
    }
    return mockMvc.perform(body == null ? request : request.content(body));
  }

  private static String block(int index, int chars) {
    return String.valueOf((char) ('a' + index)).repeat(chars);
  }

  /** {@code count} alternating messages (user first) of {@code chars} characters; ends on a user. */
  private AiConversation seeded(ModelTarget target, int count, int chars) {
    AiConversation conversation = seedConversation(conversations, "alice", "c", target);
    for (int i = 0; i < count; i++) {
      if (i % 2 == 0) {
        seedUser(conversations, messages, conversation, block(i, chars));
      } else {
        seedAssistant(conversations, messages, conversation, block(i, chars));
      }
    }
    return conversation;
  }

  private JsonNode body(ResultActions actions) throws Exception {
    return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString());
  }

  // ---- preflight ---------------------------------------------------------------------------

  @Test
  void shouldReportUsageForTheConversationTargetWhenNothingIsTrimmed() throws Exception {
    AiConversation conversation = seeded(new ModelTarget(LLAMA, nodeA), 7, 1500); // 7 x 504

    call(get("/ai/conversations/" + conversation.getId() + "/context"), alice, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model").value(LLAMA))
        .andExpect(jsonPath("$.nodeId").value(nodeA))
        .andExpect(jsonPath("$.contextLength").value(8192))
        .andExpect(jsonPath("$.usedTokens").value(7 * 504))
        .andExpect(jsonPath("$.totalTokens").value(7 * 504))
        .andExpect(jsonPath("$.fits").value(true))
        .andExpect(jsonPath("$.trimmedCount").value(0))
        .andExpect(jsonPath("$.summaryActive").value(false));
  }

  @Test
  void shouldTrimMoreForASmallerWindowModelAndReflectItInThePreflight() throws Exception {
    AiConversation conversation = seeded(new ModelTarget(LLAMA, nodeA), 7, 1500);
    String path = "/ai/conversations/" + conversation.getId() + "/context";

    call(get(path), alice, null)
        .andExpect(jsonPath("$.trimmedCount").value(0))
        .andExpect(jsonPath("$.fits").value(true));
    call(get(path).param("model", TINY).param("nodeId", nodeA), alice, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model").value(TINY))
        .andExpect(jsonPath("$.nodeId").value(nodeA))
        .andExpect(jsonPath("$.contextLength").value(1024))
        .andExpect(jsonPath("$.trimmedCount").value(6))
        .andExpect(jsonPath("$.fits").value(false))
        .andExpect(jsonPath("$.usedTokens").value(504))
        .andExpect(jsonPath("$.totalTokens").value(7 * 504));
  }

  @Test
  void shouldResolveAutoWhenThePreflightNamesNoNode() throws Exception {
    AiConversation conversation = seeded(new ModelTarget(LLAMA, nodeA), 3, 100);

    call(
            get("/ai/conversations/" + conversation.getId() + "/context").param("model", TINY),
            alice,
            null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nodeId").isString());
  }

  @Test
  void shouldUseTheSummaryOnlyWhenTheFullHistoryDoesNotFit() throws Exception {
    AiConversation conversation = seeded(new ModelTarget(LLAMA, nodeA), 7, 1500);
    AiConversation stored = conversations.findById(conversation.getId()).orElseThrow();
    stored.setSummary(new StoredSummary("earlier", 2, 10, LLAMA, nodeA, false, Instant.now()));
    conversations.save(stored);
    String path = "/ai/conversations/" + conversation.getId() + "/context";

    call(get(path), alice, null).andExpect(jsonPath("$.summaryActive").value(false));
    call(get(path).param("model", TINY).param("nodeId", nodeA), alice, null)
        .andExpect(jsonPath("$.summaryActive").value(true))
        .andExpect(jsonPath("$.trimmedCount").value(4)); // 1..2 covered, 3..6 trimmed, 7 sent
  }

  @Test
  void shouldAnswer503WhenTheTargetCannotBeResolved() throws Exception {
    AiConversation noTarget = seedConversation(conversations, "alice", "c", null);
    AiConversation ghost = seeded(new ModelTarget("ghost:7b", nodeA), 1, 10);
    AiConversation offline = seeded(new ModelTarget(LLAMA, nodeA), 1, 10);

    call(get("/ai/conversations/" + noTarget.getId() + "/context"), alice, null)
        .andExpect(status().isServiceUnavailable());
    call(get("/ai/conversations/" + ghost.getId() + "/context"), alice, null)
        .andExpect(status().isServiceUnavailable());
    call(get("/ai/conversations/" + offline.getId() + "/context").param("model", "ghost:7b"), alice, null)
        .andExpect(status().isServiceUnavailable());
    AiNode stale = aiNodeRepository.findById(nodeA).orElseThrow();
    stale.setLastHeartbeatAt(Instant.now().minus(1, ChronoUnit.HOURS));
    aiNodeRepository.save(stale);
    call(get("/ai/conversations/" + offline.getId() + "/context"), alice, null)
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void shouldShowNullContextInTheDetailWhenTheTargetCannotBeResolved() throws Exception {
    AiConversation noTarget = seedConversation(conversations, "alice", "c", null);
    AiConversation withTarget = seeded(new ModelTarget(LLAMA, nodeA), 3, 100);

    call(get("/ai/conversations/" + noTarget.getId()), alice, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.context").isEmpty());
    call(get("/ai/conversations/" + withTarget.getId()), alice, null)
        .andExpect(jsonPath("$.context.model").value(LLAMA))
        .andExpect(jsonPath("$.context.nodeId").value(nodeA))
        .andExpect(jsonPath("$.context.contextLength").value(8192))
        .andExpect(jsonPath("$.context.fits").value(true));
  }

  // ---- trimming as seen by a turn ----------------------------------------------------------

  @Test
  void shouldSendOnlyTheNewestMessagesThatFitAndReportTrimmedCountInMeta() throws Exception {
    AiConversation conversation = seeded(new ModelTarget(TINY, nodeA), 7, 400); // 7 x 134
    String body =
        call(
                post("/ai/conversations/" + conversation.getId() + "/messages"),
                alice,
                "{\"content\":\"q\"}")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode meta = parse(body).get(0).data();
    JsonNode sent = MAPPER.readTree(alpha.chatBodies().get(0)).path("messages");
    assertTrue(meta.path("context").path("trimmedCount").asInt() > 0);
    assertEquals(1024, meta.path("contextLength").asInt());
    assertTrue(sent.size() < 8 && sent.size() >= 2, "sent " + sent.size());
    assertEquals("q", sent.get(sent.size() - 1).path("content").asText());
    // The oldest messages were left out of the prompt, never deleted.
    assertFalse(sent.get(0).path("content").asText().startsWith("a"));
    assertEquals(
        9, messages.findByConversationIdOrderBySeqAsc(conversation.getId()).size());
  }

  @Test
  void shouldSendTheSummaryAsASystemMessageWhenHistoryOverflowsAndOneExists() throws Exception {
    AiConversation conversation = seeded(new ModelTarget(TINY, nodeA), 9, 400);
    AiConversation stored = conversations.findById(conversation.getId()).orElseThrow();
    stored.setSummary(new StoredSummary("EARLIER-FACTS", 2, 10, TINY, nodeA, false, Instant.now()));
    conversations.save(stored);

    String sse =
        call(
                post("/ai/conversations/" + conversation.getId() + "/messages"),
                alice,
                "{\"content\":\"q\"}")
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode sent = MAPPER.readTree(alpha.chatBodies().get(0)).path("messages");
    assertEquals("system", sent.get(0).path("role").asText());
    assertEquals(
        "Summary of the earlier conversation:\nEARLIER-FACTS", sent.get(0).path("content").asText());
    assertTrue(parse(sse).get(0).data().path("context").path("summaryActive").asBoolean());
  }

  // ---- summarize ---------------------------------------------------------------------------

  @Test
  void shouldSummariseTheTrimmedMessagesAndStoreTheSummary() throws Exception {
    alpha.completionReply("SUMMARY TEXT");
    AiConversation conversation = seeded(new ModelTarget(TINY, nodeA), 7, 400);

    JsonNode response =
        body(
            call(post("/ai/conversations/" + conversation.getId() + "/summarize"), alice, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.id").value(conversation.getId()))
                .andExpect(jsonPath("$.summary.text").value("SUMMARY TEXT"))
                .andExpect(jsonPath("$.summary.coversThroughSeq").value(2))
                .andExpect(jsonPath("$.summary.partial").value(false))
                .andExpect(jsonPath("$.summary.model").value(TINY))
                .andExpect(jsonPath("$.context.summaryActive").value(true))
                .andExpect(jsonPath("$.context.fits").value(true)));

    assertTrue(response.path("summary").path("createdAt").isString());
    StoredSummary stored = conversations.findById(conversation.getId()).orElseThrow().getSummary();
    assertNotNull(stored);
    assertEquals("SUMMARY TEXT", stored.text());
    assertEquals(2, stored.coversThroughSeq());
    assertEquals(nodeA, stored.nodeId());
    JsonNode request = MAPPER.readTree(alpha.chatBodies().get(0));
    assertFalse(request.path("stream").asBoolean());
    assertEquals(TINY, request.path("model").asText());
    assertEquals(600, request.path("options").path("num_predict").asInt());
    String transcript = request.path("messages").get(1).path("content").asText();
    assertTrue(transcript.contains(block(0, 400)) && transcript.contains(block(1, 400)));
    assertFalse(transcript.contains(block(2, 400)), "only the trimmed messages are summarised");
  }

  @Test
  void shouldMarkTheSummaryPartialWhenTheTrimmedRangeDoesNotFitTheWindowEither() throws Exception {
    alpha.completionReply("SHORT");
    AiConversation conversation = seeded(new ModelTarget(TINY, nodeA), 9, 1500);

    call(post("/ai/conversations/" + conversation.getId() + "/summarize"), alice, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.partial").value(true))
        .andExpect(jsonPath("$.summary.coversThroughSeq").value(8));

    String sent = alpha.chatBodies().get(0);
    assertTrue(sent.length() < 4000, "the transcript was cut to fit: " + sent.length());
  }

  @Test
  void shouldFoldTheEarlierSummaryIntoTheNewOne() throws Exception {
    alpha.completionReply("NEWER SUMMARY");
    AiConversation conversation = seeded(new ModelTarget(TINY, nodeA), 9, 400);
    AiConversation stored = conversations.findById(conversation.getId()).orElseThrow();
    stored.setSummary(new StoredSummary("EARLIER", 2, 10, TINY, nodeA, false, Instant.now()));
    conversations.save(stored);

    call(post("/ai/conversations/" + conversation.getId() + "/summarize"), alice, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.text").value("NEWER SUMMARY"))
        .andExpect(jsonPath("$.summary.coversThroughSeq").value(4))
        .andExpect(jsonPath("$.summary.partial").value(false));

    String request = alpha.chatBodies().get(0);
    assertTrue(request.contains("Earlier summary:"), request);
    assertTrue(request.contains("EARLIER"), request);
    assertEquals(
        4, conversations.findById(conversation.getId()).orElseThrow().getSummary().coversThroughSeq());
  }

  @Test
  void shouldAnswer409WhenNothingIsTrimmed() throws Exception {
    AiConversation conversation = seeded(new ModelTarget(TINY, nodeA), 2, 20);

    call(post("/ai/conversations/" + conversation.getId() + "/summarize"), alice, null)
        .andExpect(status().isConflict());

    assertNull(conversations.findById(conversation.getId()).orElseThrow().getSummary());
    assertEquals(0, alpha.chatCalls.get());
  }

  @Test
  void shouldAnswer400WithoutATargetAnd503WhenNoNodeCanServe() throws Exception {
    AiConversation noTarget = seeded(null, 3, 20);
    AiConversation offline = seeded(new ModelTarget(TINY, nodeA), 7, 400);
    AiNode stale = aiNodeRepository.findById(nodeA).orElseThrow();
    stale.setLastHeartbeatAt(Instant.now().minus(1, ChronoUnit.HOURS));
    aiNodeRepository.save(stale);

    call(post("/ai/conversations/" + noTarget.getId() + "/summarize"), alice, null)
        .andExpect(status().isBadRequest());
    call(post("/ai/conversations/" + offline.getId() + "/summarize"), alice, null)
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void shouldAnswer503AndKeepTheOldSummaryWhenTheModelCallFails() throws Exception {
    alpha.completionStatus(500);
    AiConversation conversation = seeded(new ModelTarget(TINY, nodeA), 7, 400);

    call(post("/ai/conversations/" + conversation.getId() + "/summarize"), alice, null)
        .andExpect(status().isServiceUnavailable());

    assertNull(conversations.findById(conversation.getId()).orElseThrow().getSummary());
  }

  @Test
  void shouldNotSummariseSomeoneElsesConversation() throws Exception {
    AiConversation conversation = seeded(new ModelTarget(TINY, nodeA), 7, 400);

    call(post("/ai/conversations/" + conversation.getId() + "/summarize"), bob, null)
        .andExpect(status().isNotFound());
    call(get("/ai/conversations/" + conversation.getId() + "/context"), bob, null)
        .andExpect(status().isNotFound());
  }
}
