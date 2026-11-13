package org.fitznet.fitznetapi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.service.ai.NodeActivityTracker;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** Heartbeat VRAM fields and the effective status shown by {@code /node/list}. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EmbeddedMongoTestConfiguration.class)
class NodeHeartbeatIntegrationTest {

  private static final String NODE_KEY = "node-secret";

  @Autowired private MockMvc mockMvc;
  @Autowired private AiNodeRepository aiNodeRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private NodeActivityTracker activityTracker;

  @BeforeEach
  void setUp() {
    aiNodeRepository.deleteAll();
    aiNodeRepository.save(
        AiNode.builder()
            .id("node-1")
            .name("rig")
            .apiKeyHash(passwordEncoder.encode(NODE_KEY))
            .status(NodeStatus.ONLINE)
            .os("Windows 11")
            .models(List.of("llama3.1:8b"))
            .vramGb(16.0)
            .address("127.0.0.1:11434")
            .registeredAt(Instant.now())
            .lastHeartbeatAt(Instant.now())
            .build());
  }

  @AfterEach
  void tearDown() {
    aiNodeRepository.deleteAll();
  }

  private void heartbeat(String body) throws Exception {
    mockMvc
        .perform(
            post("/node/heartbeat")
                .header("X-Node-Id", "node-1")
                .header("X-Node-Key", NODE_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void shouldStoreHeartbeatVramWithReportedTimestamp() throws Exception {
    heartbeat("{\"status\":\"ONLINE\",\"vramTotalMb\":16384,\"vramUsedMb\":9216}");

    AiNode node = aiNodeRepository.findById("node-1").orElseThrow();
    assertEquals(16384, node.getVramTotalMb());
    assertEquals(9216, node.getVramUsedMb());
    assertNotNull(node.getVramReportedAt());
  }

  @Test
  void shouldLeaveVramUntouchedWhenHeartbeatOmitsIt() throws Exception {
    heartbeat("{\"vramTotalMb\":16384,\"vramUsedMb\":9216}");
    Instant reportedAt = aiNodeRepository.findById("node-1").orElseThrow().getVramReportedAt();

    heartbeat("{\"status\":\"ONLINE\",\"models\":[\"llama3.1:8b\"]}");

    AiNode node = aiNodeRepository.findById("node-1").orElseThrow();
    assertEquals(16384, node.getVramTotalMb());
    assertEquals(9216, node.getVramUsedMb());
    assertEquals(reportedAt, node.getVramReportedAt());
  }

  @Test
  void shouldUpdateOnlyTheVramFieldThatWasSent() throws Exception {
    heartbeat("{\"vramTotalMb\":16384,\"vramUsedMb\":9216}");

    heartbeat("{\"vramUsedMb\":1024}");

    AiNode node = aiNodeRepository.findById("node-1").orElseThrow();
    assertEquals(16384, node.getVramTotalMb());
    assertEquals(1024, node.getVramUsedMb());
  }

  @Test
  void shouldAcceptAnOldInstallerHeartbeatWithNoVramAndStoreNone() throws Exception {
    heartbeat("{\"status\":\"ONLINE\"}");

    AiNode node = aiNodeRepository.findById("node-1").orElseThrow();
    assertNull(node.getVramTotalMb());
    assertNull(node.getVramUsedMb());
    assertNull(node.getVramReportedAt());
  }

  @Test
  void shouldRejectNegativeVram() throws Exception {
    mockMvc
        .perform(
            post("/node/heartbeat")
                .header("X-Node-Id", "node-1")
                .header("X-Node-Key", NODE_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vramTotalMb\":-1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldListStaleHeartbeatNodeAsOffline() throws Exception {
    AiNode node = aiNodeRepository.findById("node-1").orElseThrow();
    node.setLastHeartbeatAt(Instant.now().minus(10, ChronoUnit.MINUTES));
    aiNodeRepository.save(node);

    mockMvc
        .perform(get("/node/list"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("OFFLINE"));
  }

  @Test
  void shouldListNodeWithAGenerationInFlightAsBusy() throws Exception {
    try (NodeActivityTracker.Generation ignored = activityTracker.begin("node-1")) {
      mockMvc
          .perform(get("/node/list"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].status").value("BUSY"));
    }

    mockMvc.perform(get("/node/list")).andExpect(jsonPath("$[0].status").value("ONLINE"));
  }

  @Test
  void shouldKeepTheNodeListWireShapeUnchanged() throws Exception {
    mockMvc
        .perform(get("/node/list"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].*").value(Matchers.hasSize(8)))
        .andExpect(jsonPath("$[0].id").value("node-1"))
        .andExpect(jsonPath("$[0].vramGb").value(16.0))
        .andExpect(jsonPath("$[0].address").doesNotExist())
        .andExpect(jsonPath("$[0].vramTotalMb").doesNotExist());
  }
}
