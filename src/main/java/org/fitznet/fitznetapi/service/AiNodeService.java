package org.fitznet.fitznetapi.service;

import java.io.OutputStream;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.fitznet.fitznetapi.dto.NodeDto;
import org.fitznet.fitznetapi.dto.requests.ChatMessageDto;
import org.fitznet.fitznetapi.dto.requests.ChatRequestDto;
import org.fitznet.fitznetapi.dto.requests.NodeRegisterRequestDto;
import org.fitznet.fitznetapi.dto.responses.ChatResponseDto;
import org.fitznet.fitznetapi.dto.responses.NodeRegisterResponseDto;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiNodeService {

  private static final Logger logger = LoggerFactory.getLogger(AiNodeService.class);

  private final AiNodeRepository aiNodeRepository;
  private final NodeEnrollmentService nodeEnrollmentService;
  private final PasswordEncoder passwordEncoder;
  private final RestClient ollamaRestClient;
  private final OllamaChatStreamer ollamaChatStreamer;
  private final SecureRandom secureRandom = new SecureRandom();

  public AiNodeService(
      AiNodeRepository aiNodeRepository,
      NodeEnrollmentService nodeEnrollmentService,
      PasswordEncoder passwordEncoder,
      RestClient ollamaRestClient,
      OllamaChatStreamer ollamaChatStreamer) {
    this.aiNodeRepository = aiNodeRepository;
    this.nodeEnrollmentService = nodeEnrollmentService;
    this.passwordEncoder = passwordEncoder;
    this.ollamaRestClient = ollamaRestClient;
    this.ollamaChatStreamer = ollamaChatStreamer;
  }

  public NodeRegisterResponseDto register(NodeRegisterRequestDto request) {
    nodeEnrollmentService.consumeToken(request.getToken());

    byte[] randomBytes = new byte[32];
    secureRandom.nextBytes(randomBytes);
    String nodeKey = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

    AiNode node =
        AiNode.builder()
            .name(request.getName())
            .apiKeyHash(passwordEncoder.encode(nodeKey))
            .status(NodeStatus.ONLINE)
            .os(request.getOs())
            .models(request.getModels())
            .vramGb(request.getVramGb())
            .address(request.getAddress())
            .registeredAt(Instant.now())
            .lastHeartbeatAt(Instant.now())
            .build();

    AiNode savedNode = aiNodeRepository.save(node);
    logger.info("Registered AI node: {} ({})", savedNode.getName(), savedNode.getId());
    return new NodeRegisterResponseDto(savedNode.getId(), nodeKey);
  }

  public void heartbeat(
      String nodeId, String nodeKey, NodeStatus status, List<String> models, String address) {
    AiNode node =
        aiNodeRepository
            .findById(nodeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown node"));

    if (!passwordEncoder.matches(nodeKey, node.getApiKeyHash())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid node key");
    }

    node.setStatus(status != null ? status : NodeStatus.ONLINE);
    node.setLastHeartbeatAt(Instant.now());
    if (models != null) {
      node.setModels(models);
    }
    if (address != null) {
      node.setAddress(address);
    }
    aiNodeRepository.save(node);
  }

  public List<NodeDto> listNodes() {
    return aiNodeRepository.findAll().stream().map(this::toDto).toList();
  }

  /** A node deregistering itself (e.g. its uninstall script), authenticated by its own key. */
  public void deleteNode(String nodeId, String nodeKey) {
    AiNode node =
        aiNodeRepository
            .findById(nodeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown node"));

    if (!passwordEncoder.matches(nodeKey, node.getApiKeyHash())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid node key");
    }

    aiNodeRepository.deleteById(nodeId);
    logger.info("Deregistered AI node: {} ({})", node.getName(), nodeId);
  }

  /**
   * Looks up the node and picks the model to run. Throws before anything is written so a streamed
   * response can still fail with a proper HTTP status.
   */
  public ChatTarget resolveChatTarget(String nodeId, String requestedModel) {
    AiNode node =
        aiNodeRepository
            .findById(nodeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Node not found"));

    if (node.getAddress() == null || node.getAddress().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Node has no reachable address");
    }

    List<String> availableModels = node.getModels();
    if (availableModels == null || availableModels.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Node has no available models");
    }

    String model =
        (requestedModel != null && availableModels.contains(requestedModel))
            ? requestedModel
            : availableModels.get(0);
    return new ChatTarget(nodeId, node.getAddress(), model);
  }

  /**
   * The conversation to send: the supplied history when present, else the single {@code prompt}.
   * The last turn must be from the user.
   */
  public static List<ChatMessageDto> toMessages(ChatRequestDto request) {
    List<ChatMessageDto> history = request.getMessages();
    if (history != null && !history.isEmpty()) {
      if (!"user".equals(history.get(history.size() - 1).getRole())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "The last message must be from the user");
      }
      return history;
    }
    String prompt = request.getPrompt();
    if (prompt == null || prompt.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Either prompt or messages is required");
    }
    return List.of(new ChatMessageDto("user", prompt));
  }

  public void streamChat(ChatTarget target, List<ChatMessageDto> messages, OutputStream out) {
    ollamaChatStreamer.stream(target, messages, out);
  }

  public void streamChat(ChatTarget target, List<ChatMessageDto> messages, ChatEventSink sink) {
    ollamaChatStreamer.stream(target, messages, sink);
  }

  public ChatResponseDto chat(String nodeId, List<ChatMessageDto> messages, String requestedModel) {
    ChatTarget target = resolveChatTarget(nodeId, requestedModel);
    String model = target.model();

    Map<String, Object> ollamaRequest =
        Map.of(
            "model", model,
            "messages",
                messages.stream()
                    .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                    .toList(),
            "stream", false);

    try {
      // Build the full literal URL rather than substituting a "{address}" URI
      // template variable: RestClient percent-encodes expanded template
      // values, which turns the ":" between host and port into "%3A" and
      // breaks the request entirely.
      Map<String, Object> ollamaResponse =
          ollamaRestClient
              .post()
              .uri("http://" + target.address() + "/api/chat")
              .body(ollamaRequest)
              .retrieve()
              .body(Map.class);

      @SuppressWarnings("unchecked")
      Map<String, Object> message =
          ollamaResponse != null ? (Map<String, Object>) ollamaResponse.get("message") : null;
      String reply = message != null ? String.valueOf(message.get("content")) : "";

      return new ChatResponseDto(reply, model, nodeId);
    } catch (RestClientException ex) {
      logger.warn(
          "Failed to reach node {} at {}: {}", nodeId, target.address(), ex.getMessage());
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Could not reach node: " + ex.getMessage());
    }
  }

  private NodeDto toDto(AiNode node) {
    return new NodeDto(
        node.getId(),
        node.getName(),
        node.getStatus(),
        node.getOs(),
        node.getModels(),
        node.getVramGb(),
        node.getRegisteredAt(),
        node.getLastHeartbeatAt());
  }
}
