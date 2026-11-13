package org.fitznet.fitznetapi.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.fitznet.fitznetapi.dto.requests.CreateConversationRequestDto;
import org.fitznet.fitznetapi.dto.requests.EditMessageRequestDto;
import org.fitznet.fitznetapi.dto.requests.RegenerateMessageRequestDto;
import org.fitznet.fitznetapi.dto.requests.SendMessageRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateConversationRequestDto;
import org.fitznet.fitznetapi.dto.responses.ContextUsageDto;
import org.fitznet.fitznetapi.dto.responses.ConversationDetailDto;
import org.fitznet.fitznetapi.dto.responses.ConversationDto;
import org.fitznet.fitznetapi.dto.responses.ConversationListDto;
import org.fitznet.fitznetapi.dto.responses.DeleteResponseDto;
import org.fitznet.fitznetapi.dto.responses.SummarizeResponseDto;
import org.fitznet.fitznetapi.service.ChatEventSink;
import org.fitznet.fitznetapi.service.SseChatEventSink;
import org.fitznet.fitznetapi.service.ai.ContextService;
import org.fitznet.fitznetapi.service.ai.ConversationService;
import org.fitznet.fitznetapi.service.ai.TurnService;
import org.fitznet.fitznetapi.service.ai.TurnService.Kind;
import org.fitznet.fitznetapi.service.ai.TurnService.PreparedTurn;
import org.fitznet.fitznetapi.service.ai.TurnService.TurnCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Matt-GPT v2 conversations and turns (contract sections 6 to 8). Every endpoint needs
 * {@code AI_USER} or {@code ADMIN} and is scoped to the caller: someone else's conversation is a
 * 404. The streaming endpoints write Server-Sent Events synchronously to the servlet response; the
 * gRPC {@code AiChatService} calls the same methods (the streaming ones through the
 * {@link ChatEventSink} overloads).
 */
@RestController
@RequestMapping("/ai/conversations")
@PreAuthorize("hasAnyAuthority('PERM_AI_USER','PERM_ADMIN')")
public class AiConversationController {

  private static final Logger log = LoggerFactory.getLogger(AiConversationController.class);

  private final ConversationService conversationService;
  private final ContextService contextService;
  private final TurnService turnService;
  private final ObjectMapper objectMapper;

  public AiConversationController(
      ConversationService conversationService,
      ContextService contextService,
      TurnService turnService,
      ObjectMapper objectMapper) {
    this.conversationService = conversationService;
    this.contextService = contextService;
    this.turnService = turnService;
    this.objectMapper = objectMapper;
  }

  @GetMapping
  public ConversationListDto listConversations(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Integer offset) {
    return conversationService.list(currentUsername(), q, limit, offset);
  }

  @PostMapping
  public ConversationDto createConversation(
      @RequestBody(required = false) @Valid CreateConversationRequestDto request) {
    return conversationService.create(
        currentUsername(), request == null ? new CreateConversationRequestDto() : request);
  }

  @GetMapping("/{id}")
  public ConversationDetailDto getConversation(@PathVariable String id) {
    return conversationService.get(currentUsername(), id);
  }

  @PatchMapping("/{id}")
  public ConversationDto updateConversation(
      @PathVariable String id, @RequestBody @Valid UpdateConversationRequestDto request) {
    return conversationService.update(currentUsername(), id, request);
  }

  @DeleteMapping("/{id}")
  public DeleteResponseDto deleteConversation(@PathVariable String id) {
    return conversationService.delete(currentUsername(), id);
  }

  @PostMapping("/{id}/title")
  public ConversationDto generateTitle(@PathVariable String id) {
    return conversationService.generateTitle(currentUsername(), id);
  }

  @GetMapping("/{id}/context")
  public ContextUsageDto getContextUsage(
      @PathVariable String id,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) String nodeId) {
    return contextService.preflight(currentUsername(), id, model, nodeId);
  }

  @PostMapping("/{id}/summarize")
  public SummarizeResponseDto summarizeConversation(@PathVariable String id) {
    return contextService.summarize(currentUsername(), id);
  }

  // ---- turns (Server-Sent Events) ----------------------------------------------------------

  @PostMapping("/{id}/messages")
  public void sendMessage(
      @PathVariable String id,
      @RequestBody @Valid SendMessageRequestDto request,
      HttpServletResponse response)
      throws IOException {
    streamTo(response, sendCommand(id, request));
  }

  /** gRPC {@code SendMessage}: same checks, events to a sink. */
  public void sendMessage(String id, SendMessageRequestDto request, ChatEventSink sink) {
    streamTo(sink, sendCommand(id, request));
  }

  @PostMapping("/{id}/messages/{messageId}/regenerate")
  public void regenerateMessage(
      @PathVariable String id,
      @PathVariable String messageId,
      @RequestBody(required = false) @Valid RegenerateMessageRequestDto request,
      HttpServletResponse response)
      throws IOException {
    streamTo(response, regenerateCommand(id, messageId, request));
  }

  /** gRPC {@code RegenerateMessage}. */
  public void regenerateMessage(
      String id, String messageId, RegenerateMessageRequestDto request, ChatEventSink sink) {
    streamTo(sink, regenerateCommand(id, messageId, request));
  }

  @PostMapping("/{id}/messages/{messageId}/edit")
  public void editMessage(
      @PathVariable String id,
      @PathVariable String messageId,
      @RequestBody @Valid EditMessageRequestDto request,
      HttpServletResponse response)
      throws IOException {
    streamTo(response, editCommand(id, messageId, request));
  }

  /** gRPC {@code EditMessage}. */
  public void editMessage(
      String id, String messageId, EditMessageRequestDto request, ChatEventSink sink) {
    streamTo(sink, editCommand(id, messageId, request));
  }

  private TurnCommand sendCommand(String id, SendMessageRequestDto request) {
    return new TurnCommand(
        Kind.SEND,
        id,
        null,
        request.getContent(),
        false,
        request.getAttachmentIds(),
        request.getTarget(),
        System.nanoTime());
  }

  private TurnCommand regenerateCommand(
      String id, String messageId, RegenerateMessageRequestDto request) {
    return new TurnCommand(
        Kind.REGENERATE,
        id,
        messageId,
        null,
        false,
        null,
        request == null ? null : request.getTarget(),
        System.nanoTime());
  }

  private TurnCommand editCommand(String id, String messageId, EditMessageRequestDto request) {
    return new TurnCommand(
        Kind.EDIT,
        id,
        messageId,
        request.getContent(),
        request.isAttachmentIdsPresent(),
        request.getAttachmentIds(),
        request.getTarget(),
        System.nanoTime());
  }

  /**
   * Validates first (a 400/404/409 is still a normal JSON response because nothing was written),
   * then opens the event stream and runs the turn.
   */
  private void streamTo(HttpServletResponse response, TurnCommand command) throws IOException {
    PreparedTurn turn = turnService.prepare(currentUsername(), command);
    OutputStream out;
    try {
      response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
      // Tell nginx not to buffer, so tokens reach the browser as they arrive.
      response.setHeader("X-Accel-Buffering", "no");
      out = response.getOutputStream();
    } catch (IOException | RuntimeException ex) {
      turnService.abort(turn);
      throw ex;
    }
    log.debug("Streaming turn {} on conversation {}", command.kind(), command.conversationId());
    turnService.run(turn, new SseChatEventSink(out, objectMapper));
  }

  private void streamTo(ChatEventSink sink, TurnCommand command) {
    PreparedTurn turn = turnService.prepare(currentUsername(), command);
    turnService.run(turn, sink);
  }

  private static String currentUsername() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
