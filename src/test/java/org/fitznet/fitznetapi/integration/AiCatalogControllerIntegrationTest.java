package org.fitznet.fitznetapi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.AiUserPrefs;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.repository.AiUserPrefsRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.ai.FakeOllama;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** {@code /ai/models}, {@code /ai/warmup} and {@code /ai/preferences} over MockMvc. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EmbeddedMongoTestConfiguration.class)
class AiCatalogControllerIntegrationTest {

  private static final String MODEL = "llama3.1:8b";

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private UserRepository userRepository;
  @Autowired private AiNodeRepository aiNodeRepository;
  @Autowired private AiUserPrefsRepository prefsRepository;

  private FakeOllama ollama;
  private String nodeId;
  private String aiToken;
  private String otherAiToken;
  private String adminToken;
  private String plainToken;

  @BeforeEach
  void setUp() throws Exception {
    ollama = new FakeOllama();
    ollama
        .tags(FakeOllama.tag(MODEL, "sha-1", 4_920_753_328L, "llama", "8.0B", "Q4_K_M"))
        .ps(FakeOllama.loaded(MODEL, 5_300L * 1024 * 1024, "2026-11-12T13:33:00-05:00"))
        .show(MODEL, 131_072, "completion", "vision");

    userRepository.deleteAll();
    prefsRepository.deleteAll();
    aiToken = saveUser("aiuser", "USER", "AI_USER");
    otherAiToken = saveUser("other", "USER", "AI_USER");
    adminToken = saveUser("admin", "USER", "ADMIN");
    plainToken = saveUser("plain", "USER");

    // A fresh node id per test: the catalogue caches by node id across the shared Spring context.
    nodeId = "node-" + UUID.randomUUID();
    aiNodeRepository.deleteAll();
    aiNodeRepository.save(
        AiNode.builder()
            .id(nodeId)
            .name("Matt-PC")
            .status(NodeStatus.ONLINE)
            .os("Windows 11")
            .models(List.of(MODEL))
            .vramGb(16.0)
            .address(ollama.address())
            .lastHeartbeatAt(Instant.now())
            .build());
  }

  @AfterEach
  void tearDown() {
    ollama.close();
    aiNodeRepository.deleteAll();
    prefsRepository.deleteAll();
  }

  private String saveUser(String username, String... permissions) {
    userRepository.save(
        User.builder()
            .username(username)
            .email(username + "@example.com")
            .password("x")
            .permissions(new HashSet<>(Set.of(permissions)))
            .build());
    return jwtUtil.generateToken(username);
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder builder, String token, String body) {
    MockHttpServletRequestBuilder request = builder.contentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      request = request.header("Authorization", "Bearer " + token);
    }
    return body == null ? request : request.content(body);
  }

  // ---- auth --------------------------------------------------------------------------------

  @Test
  void shouldRequireAToken() throws Exception {
    mockMvc.perform(get("/ai/models")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(json(post("/ai/warmup"), null, "{\"model\":\"" + MODEL + "\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/ai/preferences")).andExpect(status().isUnauthorized());
    mockMvc.perform(json(put("/ai/preferences"), null, "{}")).andExpect(status().isUnauthorized());
  }

  @Test
  void shouldForbidUsersWithoutAiUserPermission() throws Exception {
    mockMvc
        .perform(get("/ai/models").header("Authorization", "Bearer " + plainToken))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(json(post("/ai/warmup"), plainToken, "{\"model\":\"" + MODEL + "\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/ai/preferences").header("Authorization", "Bearer " + plainToken))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(json(put("/ai/preferences"), plainToken, "{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldAllowAdminsWithoutAiUserPermission() throws Exception {
    mockMvc
        .perform(get("/ai/models").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
  }

  // ---- /ai/models --------------------------------------------------------------------------

  @Test
  void shouldReturnTheCatalogueJsonWithoutTheNodeAddress() throws Exception {
    mockMvc
        .perform(get("/ai/models").header("Authorization", "Bearer " + aiToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.generatedAt").isString())
        .andExpect(jsonPath("$.nodes[0].id").value(nodeId))
        .andExpect(jsonPath("$.nodes[0].name").value("Matt-PC"))
        .andExpect(jsonPath("$.nodes[0].status").value("ONLINE"))
        .andExpect(jsonPath("$.nodes[0].os").value("Windows 11"))
        .andExpect(jsonPath("$.nodes[0].activeGenerations").value(0))
        .andExpect(jsonPath("$.nodes[0].catalogStale").value(false))
        .andExpect(jsonPath("$.nodes[0].vram.totalMb").value(16384))
        .andExpect(jsonPath("$.nodes[0].vram.usedMb").value(5300))
        .andExpect(jsonPath("$.nodes[0].vram.source").value("ollama"))
        .andExpect(jsonPath("$.nodes[0].models[0].name").value(MODEL))
        .andExpect(jsonPath("$.nodes[0].models[0].parameterSize").value("8.0B"))
        .andExpect(jsonPath("$.nodes[0].models[0].contextLength").value(8192))
        .andExpect(jsonPath("$.nodes[0].models[0].maxContextLength").value(131072))
        .andExpect(jsonPath("$.nodes[0].models[0].capabilities[1]").value("vision"))
        .andExpect(jsonPath("$.nodes[0].models[0].loaded").value(true))
        .andExpect(jsonPath("$.nodes[0].models[0].loadedUntil").value("2026-11-12T18:33:00Z"))
        .andExpect(jsonPath("$.models[0].name").value(MODEL))
        .andExpect(jsonPath("$.models[0].nodeIds[0]").value(nodeId))
        .andExpect(jsonPath("$.models[0].loadedOn[0]").value(nodeId))
        .andExpect(jsonPath("$.models[0].autoNodeId").value(nodeId))
        .andExpect(jsonPath("$.nodes[0].address").doesNotExist())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(Matchers.not(Matchers.containsString("127.0.0.1"))));
  }

  @Test
  void shouldBypassTheCacheWhenRefreshIsTrue() throws Exception {
    mockMvc
        .perform(get("/ai/models").header("Authorization", "Bearer " + aiToken))
        .andExpect(status().isOk());
    assertEquals(1, ollama.tagsCalls.get());

    mockMvc
        .perform(get("/ai/models").header("Authorization", "Bearer " + aiToken))
        .andExpect(status().isOk());
    assertEquals(1, ollama.tagsCalls.get(), "second call is served from the cache");

    mockMvc
        .perform(
            get("/ai/models").param("refresh", "true").header("Authorization", "Bearer " + aiToken))
        .andExpect(status().isOk());
    assertEquals(2, ollama.tagsCalls.get());
  }

  // ---- /ai/warmup --------------------------------------------------------------------------

  @Test
  void shouldAnswerWarmUpLoadedForAModelThatIsInMemory() throws Exception {
    mockMvc
        .perform(json(post("/ai/warmup"), aiToken, "{\"model\":\"" + MODEL + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("loaded"))
        .andExpect(jsonPath("$.nodeId").value(nodeId))
        .andExpect(jsonPath("$.nodeName").value("Matt-PC"))
        .andExpect(jsonPath("$.model").value(MODEL))
        .andExpect(jsonPath("$.message").isEmpty());
  }

  @Test
  void shouldAnswerWarmUpUnavailableWhenNoNodeHasTheModel() throws Exception {
    mockMvc
        .perform(json(post("/ai/warmup"), aiToken, "{\"model\":\"nope:1b\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("unavailable"))
        .andExpect(jsonPath("$.message").isString());
  }

  @Test
  void shouldRejectAWarmUpWithoutAModel() throws Exception {
    mockMvc
        .perform(json(post("/ai/warmup"), aiToken, "{\"model\":\" \"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(json(post("/ai/warmup"), aiToken, "{}")).andExpect(status().isBadRequest());
  }

  // ---- /ai/preferences ---------------------------------------------------------------------

  @Test
  void shouldReturnEmptyPreferencesForANewUser() throws Exception {
    mockMvc
        .perform(get("/ai/preferences").header("Authorization", "Bearer " + aiToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastTarget").isEmpty())
        .andExpect(jsonPath("$.lastPresetId").isEmpty())
        .andExpect(jsonPath("$.updatedAt").isEmpty());
  }

  @Test
  void shouldRoundTripPreferences() throws Exception {
    mockMvc
        .perform(
            json(
                put("/ai/preferences"),
                aiToken,
                "{\"lastTarget\":{\"model\":\"" + MODEL + "\",\"nodeId\":null},\"lastPresetId\":\"p1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastTarget.model").value(MODEL))
        .andExpect(jsonPath("$.lastTarget.nodeId").isEmpty())
        .andExpect(jsonPath("$.lastPresetId").value("p1"))
        .andExpect(jsonPath("$.updatedAt").isString());

    mockMvc
        .perform(get("/ai/preferences").header("Authorization", "Bearer " + aiToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastTarget.model").value(MODEL))
        .andExpect(jsonPath("$.lastPresetId").value("p1"));

    AiUserPrefs stored = prefsRepository.findById("aiuser").orElseThrow();
    assertEquals(MODEL, stored.getLastTarget().model());
    assertTrue(stored.getLastTarget().nodeId() == null);
  }

  @Test
  void shouldStoreAnExplicitNodeInTheLastTarget() throws Exception {
    mockMvc
        .perform(
            json(
                put("/ai/preferences"),
                aiToken,
                "{\"lastTarget\":{\"model\":\"" + MODEL + "\",\"nodeId\":\"" + nodeId + "\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastTarget.nodeId").value(nodeId));
  }

  @Test
  void shouldKeepPreferencesPerUser() throws Exception {
    mockMvc
        .perform(
            json(put("/ai/preferences"), aiToken, "{\"lastTarget\":{\"model\":\"" + MODEL + "\"}}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/ai/preferences").header("Authorization", "Bearer " + otherAiToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastTarget").isEmpty());

    mockMvc
        .perform(
            json(put("/ai/preferences"), otherAiToken, "{\"lastTarget\":{\"model\":\"phi3:mini\"}}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/ai/preferences").header("Authorization", "Bearer " + aiToken))
        .andExpect(jsonPath("$.lastTarget.model").value(MODEL));
    mockMvc
        .perform(get("/ai/preferences").header("Authorization", "Bearer " + otherAiToken))
        .andExpect(jsonPath("$.lastTarget.model").value("phi3:mini"));
  }

  @Test
  void shouldLeaveAnAbsentFieldAloneAndClearAnExplicitNull() throws Exception {
    mockMvc
        .perform(
            json(
                put("/ai/preferences"),
                aiToken,
                "{\"lastTarget\":{\"model\":\"" + MODEL + "\"},\"lastPresetId\":\"p1\"}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            json(put("/ai/preferences"), aiToken, "{\"lastTarget\":{\"model\":\"phi3:mini\"}}"))
        .andExpect(jsonPath("$.lastTarget.model").value("phi3:mini"))
        .andExpect(jsonPath("$.lastPresetId").value("p1"));

    mockMvc
        .perform(json(put("/ai/preferences"), aiToken, "{\"lastPresetId\":null}"))
        .andExpect(jsonPath("$.lastTarget.model").value("phi3:mini"))
        .andExpect(jsonPath("$.lastPresetId").isEmpty());

    mockMvc
        .perform(json(put("/ai/preferences"), aiToken, "{\"lastTarget\":null}"))
        .andExpect(jsonPath("$.lastTarget").isEmpty());
  }

  @Test
  void shouldRejectAPreferenceTargetWithoutAModel() throws Exception {
    mockMvc
        .perform(json(put("/ai/preferences"), aiToken, "{\"lastTarget\":{\"model\":\"\"}}"))
        .andExpect(status().isBadRequest());
  }
}
