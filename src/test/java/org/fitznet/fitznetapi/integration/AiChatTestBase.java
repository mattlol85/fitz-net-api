package org.fitznet.fitznetapi.integration;

import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.MAPPER;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.parse;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.seedConversation;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.integration.AiConversationTestSupport.Sse;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiPreset;
import org.fitznet.fitznetapi.model.ModelTarget;
import org.fitznet.fitznetapi.repository.AiAttachmentRepository;
import org.fitznet.fitznetapi.repository.AiConversationRepository;
import org.fitznet.fitznetapi.repository.AiMessageRepository;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.repository.AiPresetRepository;
import org.fitznet.fitznetapi.repository.AiUserPrefsRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.ai.FakeOllama;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Common setup of the part 3 tests: two fake nodes (Alpha, Bravo) carrying the standard test
 * models, users {@code alice}, {@code bob} (AI_USER), {@code admin}, {@code radarr} (AI_USER and
 * RADARR) and {@code plain}, and helpers for calls, streamed turns and seeded data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EmbeddedMongoTestConfiguration.class)
abstract class AiChatTestBase {

  @Autowired protected MockMvc mockMvc;
  @Autowired protected JwtUtil jwtUtil;
  @Autowired protected UserRepository userRepository;
  @Autowired protected AiNodeRepository aiNodeRepository;
  @Autowired protected AiConversationRepository conversations;
  @Autowired protected AiMessageRepository messages;
  @Autowired protected AiUserPrefsRepository prefs;
  @Autowired protected AiPresetRepository presets;
  @Autowired protected AiAttachmentRepository attachments;

  protected FakeOllama alpha;
  protected FakeOllama bravo;
  protected String nodeA;
  protected String nodeB;
  protected String alice;
  protected String bob;
  protected String admin;
  protected String radarr;
  protected String plain;

  @BeforeEach
  void setUpBase() throws Exception {
    alpha = AiConversationTestSupport.withModels(new FakeOllama());
    bravo = AiConversationTestSupport.withModels(new FakeOllama());
    nodeA = AiConversationTestSupport.uniqueNodeId();
    nodeB = AiConversationTestSupport.uniqueNodeId();
    clean();
    alice = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "alice", "USER", "AI_USER");
    bob = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "bob", "USER", "AI_USER");
    admin = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "admin", "USER", "ADMIN");
    radarr =
        AiConversationTestSupport.saveUser(
            userRepository, jwtUtil, "radarr", "USER", "AI_USER", "RADARR");
    plain = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "plain", "USER");
    aiNodeRepository.save(AiConversationTestSupport.node(nodeA, "Alpha", alpha.address()));
    aiNodeRepository.save(AiConversationTestSupport.node(nodeB, "Bravo", bravo.address()));
  }

  @AfterEach
  void tearDownBase() {
    alpha.close();
    bravo.close();
    clean();
  }

  private void clean() {
    userRepository.deleteAll();
    conversations.deleteAll();
    messages.deleteAll();
    prefs.deleteAll();
    presets.deleteAll();
    attachments.deleteAll();
    aiNodeRepository.deleteAll();
  }

  protected ResultActions call(MockHttpServletRequestBuilder builder, String token, String body)
      throws Exception {
    MockHttpServletRequestBuilder request = builder.contentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      request = request.header("Authorization", "Bearer " + token);
    }
    return mockMvc.perform(body == null ? request : request.content(body));
  }

  protected JsonNode json(ResultActions actions) throws Exception {
    return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString());
  }

  /** alice's conversation on the given target. */
  protected AiConversation conversation(String model, String nodeId) {
    return seedConversation(conversations, "alice", "New chat", new ModelTarget(model, nodeId));
  }

  protected String stream(MockHttpServletRequestBuilder builder, String token, String body)
      throws Exception {
    return call(builder, token, body)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  protected List<Sse> send(String conversationId, String content) throws Exception {
    return sendJson(conversationId, "{\"content\":\"" + content + "\"}");
  }

  protected List<Sse> sendJson(String conversationId, String body) throws Exception {
    return parse(
        stream(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/ai/conversations/" + conversationId + "/messages"),
            alice,
            body));
  }

  protected JsonNode detail(String conversationId) throws Exception {
    return json(
        call(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/ai/conversations/" + conversationId),
                alice,
                null)
            .andExpect(status().isOk()));
  }

  protected static JsonNode sentBody(FakeOllama ollama, int index) {
    return MAPPER.readTree(ollama.chatBodies().get(index));
  }

  /** The private preset of {@code owner} as the API would store it. */
  protected AiPreset savePreset(
      String owner,
      String name,
      String model,
      String nodeId,
      String systemPrompt,
      Double temperature,
      Integer numCtx) {
    return presets.save(
        AiPreset.builder()
            .ownerUsername(owner)
            .name(name)
            .model(model)
            .nodeId(nodeId)
            .systemPrompt(systemPrompt)
            .temperature(temperature)
            .numCtx(numCtx)
            .visibility(AiPreset.PRIVATE)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build());
  }

  protected static String base64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }
}
