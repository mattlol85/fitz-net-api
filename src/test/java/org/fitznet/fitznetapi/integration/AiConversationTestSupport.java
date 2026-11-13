package org.fitznet.fitznetapi.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.ModelTarget;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.AiConversationRepository;
import org.fitznet.fitznetapi.repository.AiMessageRepository;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.ai.FakeOllama;
import org.fitznet.fitznetapi.util.JwtUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared fixtures of the Matt-GPT conversation tests: two fake nodes ("Alpha" and "Bravo", each
 * with a few models), users, seeded conversations and an SSE parser.
 */
final class AiConversationTestSupport {

  static final String LLAMA = "llama3.1:8b";
  static final String QWEN = "qwen3:14b";
  static final String TINY = "tiny:1b";
  static final String VISION = "vision:7b";

  static final ObjectMapper MAPPER = new ObjectMapper();

  /** One parsed server-sent event. */
  record Sse(String name, JsonNode data) {}

  private AiConversationTestSupport() {}

  /** Parses an event stream, ignoring comment frames. */
  static List<Sse> parse(String body) {
    List<Sse> events = new ArrayList<>();
    for (String block : body.split("\n\n")) {
      String name = null;
      String data = null;
      for (String line : block.split("\n")) {
        if (line.startsWith("event: ")) {
          name = line.substring(7);
        } else if (line.startsWith("data: ")) {
          data = line.substring(6);
        }
      }
      if (name != null && data != null) {
        events.add(new Sse(name, MAPPER.readTree(data)));
      }
    }
    return events;
  }

  static List<String> names(List<Sse> events) {
    return events.stream().map(Sse::name).toList();
  }

  /** Teaches a fake node the three test models. */
  static FakeOllama withModels(FakeOllama ollama) {
    return ollama
        .tags(
            FakeOllama.tag(LLAMA, "sha-l", 4_920_753_328L, "llama", "8.0B", "Q4_K_M"),
            FakeOllama.tag(QWEN, "sha-q", 9_000_000_000L, "qwen3", "14B", "Q4_K_M"),
            FakeOllama.tag(TINY, "sha-t", 600_000_000L, "tiny", "1B", "Q4_0"),
            FakeOllama.tag(VISION, "sha-v", 5_000_000_000L, "llava", "7B", "Q4_0"))
        .show(LLAMA, 131_072, "completion")
        .show(QWEN, 40_960, "completion", "thinking")
        .show(TINY, 1_024, "completion")
        .show(VISION, 32_768, "completion", "vision");
  }

  static AiNode node(String id, String name, String address) {
    return AiNode.builder()
        .id(id)
        .name(name)
        .status(NodeStatus.ONLINE)
        .os("Windows 11")
        .models(List.of(LLAMA, QWEN, TINY, VISION))
        .vramGb(16.0)
        .address(address)
        .lastHeartbeatAt(Instant.now())
        .build();
  }

  /** {@code size} bytes that start with the PNG signature. */
  static byte[] png(int size) {
    return withHeader(size, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
  }

  /** {@code size} bytes that start with a JPEG marker. */
  static byte[] jpeg(int size) {
    return withHeader(size, 0xFF, 0xD8, 0xFF, 0xE0);
  }

  /** {@code size} bytes that look like a WebP container (RIFF....WEBP). */
  static byte[] webp(int size) {
    byte[] data = withHeader(size, 'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P');
    return data;
  }

  private static byte[] withHeader(int size, int... header) {
    byte[] data = new byte[Math.max(size, header.length)];
    for (int i = 0; i < header.length; i++) {
      data[i] = (byte) header[i];
    }
    for (int i = header.length; i < data.length; i++) {
      data[i] = (byte) (i % 251);
    }
    return data;
  }

  static String uniqueNodeId() {
    return "node-" + UUID.randomUUID();
  }

  static String saveUser(
      UserRepository users, JwtUtil jwt, String username, String... permissions) {
    users.save(
        User.builder()
            .username(username)
            .email(username + "@example.com")
            .password("x")
            .permissions(new HashSet<>(Set.of(permissions)))
            .build());
    return jwt.generateToken(username);
  }

  static AiConversation seedConversation(
      AiConversationRepository conversations, String owner, String title, ModelTarget target) {
    Instant now = Instant.now();
    return conversations.save(
        AiConversation.builder()
            .ownerUsername(owner)
            .title(title)
            .titleSource(AiConversation.TITLE_DEFAULT)
            .target(target)
            .nextSeq(1)
            .schemaVersion(AiConversation.SCHEMA_VERSION)
            .createdAt(now)
            .updatedAt(now)
            .build());
  }

  /** Appends a stored message directly (bypassing the API) and keeps the counters in step. */
  static AiMessage seedMessage(
      AiConversationRepository conversations,
      AiMessageRepository messages,
      AiConversation conversation,
      String role,
      String content,
      String status) {
    AiConversation fresh = conversations.findById(conversation.getId()).orElseThrow();
    AiMessage saved =
        messages.save(
            AiMessage.builder()
                .conversationId(fresh.getId())
                .ownerUsername(fresh.getOwnerUsername())
                .seq(fresh.getNextSeq())
                .parentId(fresh.getActiveLeafId())
                .role(role)
                .content(content)
                .status(status)
                .schemaVersion(AiMessage.SCHEMA_VERSION)
                .createdAt(Instant.now())
                .build());
    fresh.setNextSeq(fresh.getNextSeq() + 1);
    fresh.setActiveLeafId(saved.getId());
    fresh.setMessageCount(fresh.getMessageCount() + 1);
    conversations.save(fresh);
    return saved;
  }

  static AiMessage seedUser(
      AiConversationRepository c, AiMessageRepository m, AiConversation conv, String content) {
    return seedMessage(c, m, conv, AiMessage.ROLE_USER, content, AiMessage.STATUS_COMPLETE);
  }

  static AiMessage seedAssistant(
      AiConversationRepository c, AiMessageRepository m, AiConversation conv, String content) {
    return seedMessage(c, m, conv, AiMessage.ROLE_ASSISTANT, content, AiMessage.STATUS_COMPLETE);
  }
}
