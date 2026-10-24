package org.fitznet.fitznetapi.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.fitznet.fitznetapi.dto.NodeDto;
import org.fitznet.fitznetapi.dto.requests.ChatMessageDto;
import org.fitznet.fitznetapi.dto.requests.ChatRequestDto;
import org.fitznet.fitznetapi.dto.requests.NodeHeartbeatRequestDto;
import org.fitznet.fitznetapi.dto.requests.NodeRegisterRequestDto;
import org.fitznet.fitznetapi.dto.responses.ChatResponseDto;
import org.fitznet.fitznetapi.dto.responses.NodeRegisterResponseDto;
import org.fitznet.fitznetapi.model.NodeStatus;
import org.fitznet.fitznetapi.service.AiNodeService;
import org.fitznet.fitznetapi.service.ChatEventSink;
import org.fitznet.fitznetapi.service.ChatTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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

  @PreAuthorize("hasAnyAuthority('PERM_AI_USER','PERM_ADMIN')")
  @PostMapping("/node/{id}/chat")
  public ChatResponseDto chat(@PathVariable String id, @RequestBody @Valid ChatRequestDto request) {
    log.info("Request at /node/{}/chat", id);
    return aiNodeService.chat(id, AiNodeService.toMessages(request), request.getModel());
  }

  /**
   * Streaming variant of {@link #chat}: Server-Sent Events (meta, token*, done | error). Written
   * synchronously to the servlet response so there's no async dispatch for the JWT filter to miss.
   */
  @PreAuthorize("hasAnyAuthority('PERM_AI_USER','PERM_ADMIN')")
  @PostMapping("/node/{id}/chat/stream")
  public void chatStream(
      @PathVariable String id,
      @RequestBody @Valid ChatRequestDto request,
      HttpServletResponse response)
      throws IOException {
    log.info("Request at /node/{}/chat/stream", id);
    List<ChatMessageDto> messages = AiNodeService.toMessages(request);
    ChatTarget target = aiNodeService.resolveChatTarget(id, request.getModel());
    response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
    // Tell nginx not to buffer, so tokens reach the browser as they arrive.
    response.setHeader("X-Accel-Buffering", "no");
    aiNodeService.streamChat(target, messages, response.getOutputStream());
  }

  /** gRPC {@code NodeService/ChatStream}: same checks as {@link #chatStream}, events to a sink. */
  @PreAuthorize("hasAnyAuthority('PERM_AI_USER','PERM_ADMIN')")
  public void chatStream(String id, ChatRequestDto request, ChatEventSink sink) {
    log.info("gRPC request at NodeService/ChatStream - node {}", id);
    List<ChatMessageDto> messages = AiNodeService.toMessages(request);
    ChatTarget target = aiNodeService.resolveChatTarget(id, request.getModel());
    aiNodeService.streamChat(target, messages, sink);
  }

  @DeleteMapping("/node/{id}")
  public void deleteNode(@PathVariable String id, @RequestHeader("X-Node-Key") String nodeKey) {
    log.info("Request at DELETE /node/{}", id);
    aiNodeService.deleteNode(id, nodeKey);
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
