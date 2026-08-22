package org.fitznet.fitznetapi.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.fitznet.fitznetapi.dto.NodeDto;
import org.fitznet.fitznetapi.dto.requests.NodeRegisterRequestDto;
import org.fitznet.fitznetapi.dto.responses.NodeRegisterResponseDto;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiNodeService {

  private static final Logger logger = LoggerFactory.getLogger(AiNodeService.class);

  private final AiNodeRepository aiNodeRepository;
  private final NodeEnrollmentService nodeEnrollmentService;
  private final PasswordEncoder passwordEncoder;
  private final SecureRandom secureRandom = new SecureRandom();

  public AiNodeService(
      AiNodeRepository aiNodeRepository,
      NodeEnrollmentService nodeEnrollmentService,
      PasswordEncoder passwordEncoder) {
    this.aiNodeRepository = aiNodeRepository;
    this.nodeEnrollmentService = nodeEnrollmentService;
    this.passwordEncoder = passwordEncoder;
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
            .registeredAt(Instant.now())
            .lastHeartbeatAt(Instant.now())
            .build();

    AiNode savedNode = aiNodeRepository.save(node);
    logger.info("Registered AI node: {} ({})", savedNode.getName(), savedNode.getId());
    return new NodeRegisterResponseDto(savedNode.getId(), nodeKey);
  }

  public void heartbeat(String nodeId, String nodeKey, NodeStatus status, List<String> models) {
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
    aiNodeRepository.save(node);
  }

  public List<NodeDto> listNodes() {
    return aiNodeRepository.findAll().stream().map(this::toDto).toList();
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
