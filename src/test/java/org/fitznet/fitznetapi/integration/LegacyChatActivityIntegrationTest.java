package org.fitznet.fitznetapi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.ai.NodeActivityTracker;
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

/**
 * The deprecated {@code /node/{id}/chat*} endpoints still count toward the node's in-flight
 * generations (so it shows as BUSY) and now send {@code keep_alive}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EmbeddedMongoTestConfiguration.class)
class LegacyChatActivityIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private UserRepository userRepository;
  @Autowired private AiNodeRepository aiNodeRepository;
  @Autowired private NodeActivityTracker tracker;

  private HttpServer fakeOllama;
  private final CountDownLatch release = new CountDownLatch(1);
  private final AtomicReference<String> chatBody = new AtomicReference<>();
  private String token;

  @BeforeEach
  void setUp() throws Exception {
    fakeOllama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fakeOllama.setExecutor(Executors.newCachedThreadPool());
    fakeOllama.createContext(
        "/api/chat",
        exchange -> {
          chatBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          boolean streaming = chatBody.get().contains("\"stream\":true");
          byte[] body =
              (streaming
                      ? "{\"message\":{\"content\":\"pong\"},\"done\":false}\n"
                          + "{\"message\":{\"content\":\"\"},\"done\":true}\n"
                      : "{\"message\":{\"content\":\"pong\"},\"done\":true}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    fakeOllama.start();

    userRepository.deleteAll();
    userRepository.save(
        User.builder()
            .username("chatter")
            .email("chatter@example.com")
            .password("x")
            .permissions(new HashSet<>(Set.of("USER", "AI_USER")))
            .build());
    token = jwtUtil.generateToken("chatter");

    aiNodeRepository.deleteAll();
    aiNodeRepository.save(
        AiNode.builder()
            .id("node-1")
            .name("test-node")
            .status(NodeStatus.ONLINE)
            .models(List.of("qwen3:14b"))
            .address("127.0.0.1:" + fakeOllama.getAddress().getPort())
            .lastHeartbeatAt(Instant.now())
            .build());
  }

  @AfterEach
  void tearDown() {
    release.countDown();
    fakeOllama.stop(0);
    aiNodeRepository.deleteAll();
  }

  private void awaitActive(int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (tracker.active("node-1") != expected) {
      assertTrue(System.nanoTime() < deadline, "active generations never became " + expected);
      Thread.sleep(10);
    }
  }

  private void runWhileHeld(String path) throws Exception {
    Thread request =
        new Thread(
            () -> {
              try {
                mockMvc
                    .perform(
                        post(path)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"prompt\":\"hi\"}"))
                    .andExpect(status().isOk());
              } catch (Exception ex) {
                throw new IllegalStateException(ex);
              }
            });
    request.start();

    awaitActive(1);
    mockMvc.perform(get("/node/list")).andExpect(jsonPath("$[0].status").value("BUSY"));
    release.countDown();
    request.join(10_000);

    awaitActive(0);
    mockMvc.perform(get("/node/list")).andExpect(jsonPath("$[0].status").value("ONLINE"));
  }

  @Test
  void shouldCountAStreamingLegacyChatAndSendKeepAlive() throws Exception {
    runWhileHeld("/node/node-1/chat/stream");

    assertTrue(chatBody.get().contains("\"keep_alive\":\"30m\""), chatBody.get());
    assertEquals(0, tracker.active("node-1"));
  }

  @Test
  void shouldCountABufferedLegacyChatAndSendKeepAlive() throws Exception {
    runWhileHeld("/node/node-1/chat");

    assertTrue(chatBody.get().contains("\"keep_alive\":\"30m\""), chatBody.get());
    assertEquals(0, tracker.active("node-1"));
  }
}
