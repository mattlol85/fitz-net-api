package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import java.util.List;
import org.fitznet.fitznetapi.dto.NodeDto;
import org.fitznet.fitznetapi.dto.requests.ChatRequestDto;
import org.fitznet.fitznetapi.dto.requests.NodeHeartbeatRequestDto;
import org.fitznet.fitznetapi.dto.requests.NodeRegisterRequestDto;
import org.fitznet.fitznetapi.dto.responses.ChatResponseDto;
import org.fitznet.fitznetapi.dto.responses.NodeRegisterResponseDto;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.fitznet.fitznetapi.service.AiNodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class NodeController {

  static final Logger log = LoggerFactory.getLogger(NodeController.class);

  @Autowired AiNodeService aiNodeService;

  @PostMapping("/node/register")
  public NodeRegisterResponseDto registerNode(@RequestBody @Valid NodeRegisterRequestDto request) {
    log.info("Request at /node/register - name: {}", request.getName());
    return aiNodeService.register(request);
  }

  @PostMapping("/node/heartbeat")
  public void heartbeat(
      @RequestHeader("X-Node-Id") String nodeId,
      @RequestHeader("X-Node-Key") String nodeKey,
      @RequestBody(required = false) NodeHeartbeatRequestDto request) {
    log.debug("Request at /node/heartbeat - nodeId: {}", nodeId);
    NodeStatus status = parseStatus(request != null ? request.getStatus() : null);
    List<String> models = request != null ? request.getModels() : null;
    String address = request != null ? request.getAddress() : null;
    aiNodeService.heartbeat(nodeId, nodeKey, status, models, address);
  }

  @GetMapping("/node/list")
  public List<NodeDto> listNodes() {
    return aiNodeService.listNodes();
  }

  @PostMapping("/node/{id}/chat")
  public ChatResponseDto chat(@PathVariable String id, @RequestBody @Valid ChatRequestDto request) {
    log.info("Request at /node/{}/chat", id);
    return aiNodeService.chat(id, request.getPrompt(), request.getModel());
  }

  private NodeStatus parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return NodeStatus.ONLINE;
    }
    try {
      return NodeStatus.valueOf(status.toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid node status: " + status);
    }
  }
}
