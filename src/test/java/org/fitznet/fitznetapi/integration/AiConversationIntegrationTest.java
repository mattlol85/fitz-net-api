package org.fitznet.fitznetapi.integration;

import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.LLAMA;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.seedAssistant;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.seedConversation;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.seedUser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.ModelTarget;
import org.fitznet.fitznetapi.repository.AiConversationRepository;
import org.fitznet.fitznetapi.repository.AiMessageRepository;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.repository.AiUserPrefsRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
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

/** Conversation CRUD, ownership, ordering, search and PATCH semantics over MockMvc. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EmbeddedMongoTestConfiguration.class)
class AiConversationIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private UserRepository userRepository;
  @Autowired private AiNodeRepository aiNodeRepository;
  @Autowired private AiConversationRepository conversations;
  @Autowired private AiMessageRepository messages;
  @Autowired private AiUserPrefsRepository prefs;

  private String alice;
  private String bob;
  private String plain;

  @BeforeEach
  void setUp() {
    userRepository.deleteAll();
    conversations.deleteAll();
    messages.deleteAll();
    prefs.deleteAll();
    aiNodeRepository.deleteAll();
    alice = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "alice", "USER", "AI_USER");
    bob = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "bob", "USER", "AI_USER");
    plain = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "plain", "USER");
  }

  @AfterEach
  void tearDown() {
    conversations.deleteAll();
    messages.deleteAll();
    prefs.deleteAll();
  }

  private ResultActions call(MockHttpServletRequestBuilder builder, String token, String body)
      throws Exception {
    MockHttpServletRequestBuilder request = builder.contentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      request = request.header("Authorization", "Bearer " + token);
    }
    return mockMvc.perform(body == null ? request : request.content(body));
  }

  private String createConversation(String token, String body) throws Exception {
    String json =
        call(post("/ai/conversations"), token, body)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return AiConversationTestSupport.MAPPER.readTree(json).path("id").asText();
  }

  // ---- auth --------------------------------------------------------------------------------

  @Test
  void shouldRequireATokenOnEveryConversationEndpoint() throws Exception {
    call(get("/ai/conversations"), null, null).andExpect(status().isUnauthorized());
    call(post("/ai/conversations"), null, "{}").andExpect(status().isUnauthorized());
    call(get("/ai/conversations/x"), null, null).andExpect(status().isUnauthorized());
    call(patch("/ai/conversations/x"), null, "{}").andExpect(status().isUnauthorized());
    call(delete("/ai/conversations/x"), null, null).andExpect(status().isUnauthorized());
    call(post("/ai/conversations/x/title"), null, null).andExpect(status().isUnauthorized());
    call(get("/ai/conversations/x/context"), null, null).andExpect(status().isUnauthorized());
    call(post("/ai/conversations/x/summarize"), null, null).andExpect(status().isUnauthorized());
    call(post("/ai/conversations/x/messages"), null, "{\"content\":\"hi\"}")
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldForbidUsersWithoutAiUserPermission() throws Exception {
    call(get("/ai/conversations"), plain, null).andExpect(status().isForbidden());
    call(post("/ai/conversations"), plain, "{}").andExpect(status().isForbidden());
    call(post("/ai/conversations/x/messages"), plain, "{\"content\":\"hi\"}")
        .andExpect(status().isForbidden());
    call(post("/ai/conversations/x/summarize"), plain, null).andExpect(status().isForbidden());
  }

  // ---- CRUD --------------------------------------------------------------------------------

  @Test
  void shouldRoundTripCreateListGetPatchAndDelete() throws Exception {
    call(
            post("/ai/conversations"),
            alice,
            "{\"target\":{\"model\":\"" + LLAMA + "\"},\"settings\":{\"systemPrompt\":\"Be terse\"}}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").isString())
        .andExpect(jsonPath("$.title").value("New chat"))
        .andExpect(jsonPath("$.titleSource").value("default"))
        .andExpect(jsonPath("$.pinned").value(false))
        .andExpect(jsonPath("$.target.model").value(LLAMA))
        .andExpect(jsonPath("$.target.nodeId").isEmpty())
        .andExpect(jsonPath("$.settings.systemPrompt").value("Be terse"))
        .andExpect(jsonPath("$.settings.presetId").isEmpty())
        .andExpect(jsonPath("$.messageCount").value(0))
        .andExpect(jsonPath("$.createdAt").isString())
        .andExpect(jsonPath("$.updatedAt").isString());
    String id = conversations.findAll().get(0).getId();

    call(get("/ai/conversations"), alice, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(id))
        .andExpect(jsonPath("$.hasMore").value(false));

    call(get("/ai/conversations/" + id), alice, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.messages.length()").value(0))
        .andExpect(jsonPath("$.summary").isEmpty());

    call(patch("/ai/conversations/" + id), alice, "{\"title\":\"Renamed\",\"pinned\":true}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Renamed"))
        .andExpect(jsonPath("$.titleSource").value("user"))
        .andExpect(jsonPath("$.pinned").value(true));

    call(delete("/ai/conversations/" + id), alice, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(true));
    call(get("/ai/conversations/" + id), alice, null).andExpect(status().isNotFound());
    assertEquals(0, conversations.count());
  }

  @Test
  void shouldCreateWithAnEmptyBodyAndAUserTitle() throws Exception {
    call(post("/ai/conversations"), alice, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("New chat"))
        .andExpect(jsonPath("$.target").isEmpty());
    call(post("/ai/conversations"), alice, "{\"title\":\"Mine\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.titleSource").value("user"));
  }

  @Test
  void shouldRejectOversizedTitleAndSystemPrompt() throws Exception {
    call(post("/ai/conversations"), alice, "{\"title\":\"" + "t".repeat(121) + "\"}")
        .andExpect(status().isBadRequest());
    call(
            post("/ai/conversations"),
            alice,
            "{\"settings\":{\"systemPrompt\":\"" + "s".repeat(8001) + "\"}}")
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldCascadeDeleteToMessages() throws Exception {
    AiConversation conversation = seedConversation(conversations, "alice", "c", null);
    seedUser(conversations, messages, conversation, "hi");
    seedAssistant(conversations, messages, conversation, "hello");

    call(delete("/ai/conversations/" + conversation.getId()), alice, null)
        .andExpect(status().isOk());

    assertEquals(0, messages.count());
  }

  // ---- ownership ---------------------------------------------------------------------------

  @Test
  void shouldAnswer404NotForbiddenForSomeoneElsesConversation() throws Exception {
    String id = createConversation(alice, "{}");

    call(get("/ai/conversations/" + id), bob, null).andExpect(status().isNotFound());
    call(patch("/ai/conversations/" + id), bob, "{\"title\":\"x\"}")
        .andExpect(status().isNotFound());
    call(delete("/ai/conversations/" + id), bob, null).andExpect(status().isNotFound());
    call(post("/ai/conversations/" + id + "/title"), bob, null).andExpect(status().isNotFound());
    call(get("/ai/conversations/" + id + "/context"), bob, null).andExpect(status().isNotFound());
    call(post("/ai/conversations/" + id + "/summarize"), bob, null)
        .andExpect(status().isNotFound());
    call(post("/ai/conversations/" + id + "/messages"), bob, "{\"content\":\"hi\"}")
        .andExpect(status().isNotFound());
    call(get("/ai/conversations"), bob, null)
        .andExpect(jsonPath("$.items.length()").value(0));
    call(get("/ai/conversations/" + id), alice, null).andExpect(status().isOk());
  }

  @Test
  void shouldNotLetSearchLeakAnotherUsersMessages() throws Exception {
    AiConversation secret = seedConversation(conversations, "alice", "secret plans", null);
    seedUser(conversations, messages, secret, "the launch code is swordfish");

    call(get("/ai/conversations").param("q", "swordfish"), bob, null)
        .andExpect(jsonPath("$.items.length()").value(0));
    call(get("/ai/conversations").param("q", "swordfish"), alice, null)
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  // ---- list order and paging ---------------------------------------------------------------

  private AiConversation seedAt(String title, boolean pinned, Instant updatedAt) {
    AiConversation conversation = seedConversation(conversations, "alice", title, null);
    conversation.setPinned(pinned);
    conversation.setUpdatedAt(updatedAt);
    return conversations.save(conversation);
  }

  @Test
  void shouldListPinnedFirstThenMostRecentlyUpdated() throws Exception {
    Instant now = Instant.now();
    seedAt("newest", false, now);
    seedAt("oldest-pinned", true, now.minus(5, ChronoUnit.DAYS));
    seedAt("middle", false, now.minus(1, ChronoUnit.DAYS));
    seedAt("recent-pinned", true, now.minus(1, ChronoUnit.HOURS));

    call(get("/ai/conversations"), alice, null)
        .andExpect(jsonPath("$.items[0].title").value("recent-pinned"))
        .andExpect(jsonPath("$.items[1].title").value("oldest-pinned"))
        .andExpect(jsonPath("$.items[2].title").value("newest"))
        .andExpect(jsonPath("$.items[3].title").value("middle"));
  }

  @Test
  void shouldPageWithLimitOffsetAndHasMore() throws Exception {
    Instant now = Instant.now();
    for (int i = 0; i < 5; i++) {
      seedAt("c" + i, false, now.minus(i, ChronoUnit.HOURS));
    }

    call(get("/ai/conversations").param("limit", "2"), alice, null)
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].title").value("c0"))
        .andExpect(jsonPath("$.hasMore").value(true));
    call(get("/ai/conversations").param("limit", "2").param("offset", "2"), alice, null)
        .andExpect(jsonPath("$.items[0].title").value("c2"))
        .andExpect(jsonPath("$.hasMore").value(true));
    call(get("/ai/conversations").param("limit", "2").param("offset", "4"), alice, null)
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.hasMore").value(false));
    call(get("/ai/conversations").param("limit", "9999"), alice, null)
        .andExpect(jsonPath("$.items.length()").value(5));
  }

  // ---- search ------------------------------------------------------------------------------

  @Test
  void shouldSearchTitleAndMessageContentCaseInsensitivelyWithASnippet() throws Exception {
    AiConversation byTitle = seedConversation(conversations, "alice", "Tuning the BELL debounce", null);
    AiConversation byContent = seedConversation(conversations, "alice", "Unrelated", null);
    seedUser(conversations, messages, byContent, "The quick brown fox jumps over the lazy dog");
    AiConversation neither = seedConversation(conversations, "alice", "Other", null);
    seedUser(conversations, messages, neither, "nothing to see here");

    call(get("/ai/conversations").param("q", "bell"), alice, null)
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(byTitle.getId()))
        .andExpect(jsonPath("$.items[0].matchSnippet").isEmpty());

    call(get("/ai/conversations").param("q", "BROWN Fox"), alice, null)
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(byContent.getId()))
        .andExpect(
            jsonPath("$.items[0].matchSnippet")
                .value(org.hamcrest.Matchers.containsString("brown fox")));
  }

  @Test
  void shouldLimitTheSnippetToOneHundredSixtyCharactersAroundTheMatch() throws Exception {
    AiConversation conversation = seedConversation(conversations, "alice", "Long", null);
    seedUser(
        conversations,
        messages,
        conversation,
        "x".repeat(400) + " NEEDLE " + "y".repeat(400));

    String body =
        call(get("/ai/conversations").param("q", "needle"), alice, null)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String snippet =
        AiConversationTestSupport.MAPPER.readTree(body).path("items").get(0).path("matchSnippet").asText();

    assertTrue(snippet.length() <= 160, "snippet length " + snippet.length());
    assertTrue(snippet.contains("NEEDLE"));
  }

  @Test
  void shouldTreatRegexMetacharactersInTheSearchTermLiterally() throws Exception {
    AiConversation dotted = seedConversation(conversations, "alice", "version a.c", null);
    AiConversation plainTitle = seedConversation(conversations, "alice", "abc", null);
    AiConversation parens = seedConversation(conversations, "alice", "x", null);
    seedUser(conversations, messages, parens, "call foo(bar) now");

    call(get("/ai/conversations").param("q", "a.c"), alice, null)
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(dotted.getId()));
    call(get("/ai/conversations").param("q", ".*"), alice, null)
        .andExpect(jsonPath("$.items.length()").value(0));
    call(get("/ai/conversations").param("q", "foo(bar"), alice, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(parens.getId()));
    call(get("/ai/conversations").param("q", "(["), alice, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
    assertTrue(plainTitle.getId() != null);
  }

  // ---- PATCH semantics ---------------------------------------------------------------------

  @Test
  void shouldLeaveAbsentFieldsAloneAndNotMoveUpdatedAtOnPin() throws Exception {
    String id =
        createConversation(
            alice,
            "{\"title\":\"Keep\",\"target\":{\"model\":\"" + LLAMA + "\",\"nodeId\":\"n1\"},"
                + "\"settings\":{\"presetId\":\"p1\",\"systemPrompt\":\"Be terse\"}}");
    Instant before = conversations.findById(id).orElseThrow().getUpdatedAt();
    Thread.sleep(20);

    call(patch("/ai/conversations/" + id), alice, "{\"pinned\":true}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pinned").value(true))
        .andExpect(jsonPath("$.title").value("Keep"))
        .andExpect(jsonPath("$.target.nodeId").value("n1"))
        .andExpect(jsonPath("$.settings.systemPrompt").value("Be terse"))
        .andExpect(jsonPath("$.settings.presetId").value("p1"));

    assertEquals(before, conversations.findById(id).orElseThrow().getUpdatedAt());
  }

  @Test
  void shouldMoveUpdatedAtOnRenameAndSettingsChange() throws Exception {
    String id = createConversation(alice, "{}");
    Instant created = conversations.findById(id).orElseThrow().getUpdatedAt();
    Thread.sleep(20);

    call(patch("/ai/conversations/" + id), alice, "{\"title\":\"New name\"}")
        .andExpect(status().isOk());
    Instant afterRename = conversations.findById(id).orElseThrow().getUpdatedAt();
    assertTrue(afterRename.isAfter(created));
    Thread.sleep(20);

    call(patch("/ai/conversations/" + id), alice, "{\"settings\":{\"systemPrompt\":\"x\"}}")
        .andExpect(status().isOk());
    assertTrue(conversations.findById(id).orElseThrow().getUpdatedAt().isAfter(afterRename));
  }

  @Test
  void shouldReplaceTargetAndSettingsWholeAndClearThemWhenSentAsNull() throws Exception {
    String id =
        createConversation(
            alice,
            "{\"target\":{\"model\":\"" + LLAMA + "\",\"nodeId\":\"n1\"},"
                + "\"settings\":{\"presetId\":\"p1\",\"systemPrompt\":\"Be terse\"}}");

    call(patch("/ai/conversations/" + id), alice, "{\"target\":{\"model\":\"other:1b\"}}")
        .andExpect(jsonPath("$.target.model").value("other:1b"))
        .andExpect(jsonPath("$.target.nodeId").isEmpty())
        .andExpect(jsonPath("$.settings.systemPrompt").value("Be terse"));

    call(
            patch("/ai/conversations/" + id),
            alice,
            "{\"settings\":{\"presetId\":null,\"systemPrompt\":null}}")
        .andExpect(jsonPath("$.settings.presetId").isEmpty())
        .andExpect(jsonPath("$.settings.systemPrompt").isEmpty())
        .andExpect(jsonPath("$.target.model").value("other:1b"));

    call(patch("/ai/conversations/" + id), alice, "{\"target\":null}")
        .andExpect(jsonPath("$.target").isEmpty());
  }

  @Test
  void shouldKeepTheUserTitleSourceAndRejectABlankTitle() throws Exception {
    String id = createConversation(alice, "{}");

    call(patch("/ai/conversations/" + id), alice, "{\"title\":\"  \"}")
        .andExpect(status().isBadRequest());
    call(patch("/ai/conversations/" + id), alice, "{\"title\":\"  Trimmed  \"}")
        .andExpect(jsonPath("$.title").value("Trimmed"))
        .andExpect(jsonPath("$.titleSource").value("user"));
  }

  @Test
  void shouldUpdateTheLastTargetPreferenceWhenAPatchCarriesATarget() throws Exception {
    String id = createConversation(alice, "{}");

    call(
            patch("/ai/conversations/" + id),
            alice,
            "{\"target\":{\"model\":\"" + LLAMA + "\",\"nodeId\":\"n1\"}}")
        .andExpect(status().isOk());

    ModelTarget last = prefs.findById("alice").orElseThrow().getLastTarget();
    assertEquals(new ModelTarget(LLAMA, "n1"), last);
  }

  // ---- stale streaming message -------------------------------------------------------------

  @Test
  void shouldReportAnOrphanedStreamingMessageAsInterrupted() throws Exception {
    AiConversation conversation = seedConversation(conversations, "alice", "c", null);
    seedUser(conversations, messages, conversation, "hi");
    AiConversationTestSupport.seedMessage(
        conversations, messages, conversation, "assistant", "", "streaming");

    call(get("/ai/conversations/" + conversation.getId()), alice, null)
        .andExpect(jsonPath("$.messages[1].status").value("error"))
        .andExpect(jsonPath("$.messages[1].error.code").value("INTERRUPTED"));
  }
}
