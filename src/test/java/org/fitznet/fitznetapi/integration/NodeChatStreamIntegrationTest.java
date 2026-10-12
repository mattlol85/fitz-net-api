package org.fitznet.fitznetapi.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(EmbeddedMongoTestConfiguration.class)
class NodeChatStreamIntegrationTest {

  private static final String BODY = "{\"prompt\":\"hi\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private UserRepository userRepository;
  @Autowired private AiNodeRepository aiNodeRepository;

  private HttpServer fakeOllama;
  private String token;
  private String plainToken;

  @BeforeEach
  void setUp() throws Exception {
    fakeOllama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fakeOllama.createContext(
        "/api/chat",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body =
              ("{\"message\":{\"content\":\"pong\"},\"done\":false}\n"
                      + "{\"message\":{\"content\":\"\"},\"done\":true}\n")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
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
    userRepository.save(
        User.builder()
            .username("plain")
            .email("plain@example.com")
            .password("x")
            .permissions(new HashSet<>(Set.of("USER")))
            .build());
    plainToken = jwtUtil.generateToken("plain");

    aiNodeRepository.deleteAll();
    aiNodeRepository.save(
        AiNode.builder()
            .id("node-1")
            .name("test-node")
            .status(NodeStatus.ONLINE)
            .models(List.of("qwen3:14b"))
            .address("127.0.0.1:" + fakeOllama.getAddress().getPort())
            .build());
  }

  @AfterEach
  void tearDown() {
    fakeOllama.stop(0);
    aiNodeRepository.deleteAll();
  }

  @Test
  void chatStream_requiresToken() throws Exception {
    mockMvc
        .perform(
            post("/node/node-1/chat/stream").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void chatStream_forbiddenWithoutAiUserPermission() throws Exception {
    mockMvc
        .perform(
            post("/node/node-1/chat/stream")
                .header("Authorization", "Bearer " + plainToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void chatStream_streamsSseWithToken() throws Exception {
    mockMvc
        .perform(
            post("/node/node-1/chat/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", Matchers.startsWith("text/event-stream")))
        .andExpect(header().string("X-Accel-Buffering", "no"))
        .andExpect(
            content()
                .string(
                    Matchers.allOf(
                        Matchers.startsWith("event: meta\n"),
                        Matchers.containsString("event: token\ndata: {\"content\":\"pong\"}\n\n"),
                        Matchers.containsString("event: done\ndata: {\"promptTokens\":"))));
  }

  @Test
  void chatStream_acceptsHistory() throws Exception {
    mockMvc
        .perform(
            post("/node/node-1/chat/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},"
                        + "{\"role\":\"assistant\",\"content\":\"hello\"},"
                        + "{\"role\":\"user\",\"content\":\"again\"}]}"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("event: token")));
  }

  @Test
  void chatStream_rejectsMissingPromptAndMessages() throws Exception {
    mockMvc
        .perform(
            post("/node/node-1/chat/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void chatStream_rejectsHistoryEndingWithAssistant() throws Exception {
    mockMvc
        .perform(
            post("/node/node-1/chat/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"assistant\",\"content\":\"hello\"}]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void chatStream_unknownNode_returns404BeforeStreaming() throws Exception {
    mockMvc
        .perform(
            post("/node/nope/chat/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isNotFound());
  }
}
