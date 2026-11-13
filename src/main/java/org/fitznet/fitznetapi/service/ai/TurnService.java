package org.fitznet.fitznetapi.service.ai;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fitznet.fitznetapi.dto.ModelTargetDto;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;
import org.fitznet.fitznetapi.model.MessageError;
import org.fitznet.fitznetapi.model.MessageGeneration;
import org.fitznet.fitznetapi.model.ModelTarget;
import org.fitznet.fitznetapi.repository.AiConversationRepository;
import org.fitznet.fitznetapi.repository.AiMessageRepository;
import org.fitznet.fitznetapi.service.ChatEventSink;
import org.fitznet.fitznetapi.service.ai.TurnResult.Ended;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The shared pipeline of the three streaming turn endpoints (contract 7.1): send, regenerate and
 * edit. Message content, thinking and prompts are never logged.
 *
 * <p>Split in two so REST can still answer with a normal JSON error: {@link #prepare} validates
 * (400/404/409, nothing persisted), takes the conversation's single generation slot and applies the
 * action (persist or truncate, create the assistant message as {@code streaming}); {@link #run}
 * then routes, builds the prompt, streams through {@link OllamaTurnStreamer}, writes exactly one
 * {@code done} or {@code error} (none when the client cancelled), and always persists the assistant
 * message and frees the slot in a {@code finally}.
 */
@Service
public class TurnService {

  private static final Logger logger = LoggerFactory.getLogger(TurnService.class);

  /** Auto failover: at most this many additional nodes after the first. */
  static final int MAX_FAILOVERS = 2;

  static final int PROVISIONAL_TITLE_CHARS = 60;

  public enum Kind {
    SEND,
    REGENERATE,
    EDIT
  }

  /**
   * @param messageId the assistant message to regenerate, or the user message to edit; null for send
   * @param attachmentIdsPresent edit only: false keeps the existing attachments
   * @param target optional: becomes the conversation's target before generating
   * @param receivedAtNanos {@link System#nanoTime()} when the request arrived (for the stats)
   */
  public record TurnCommand(
      Kind kind,
      String conversationId,
      String messageId,
      String content,
      boolean attachmentIdsPresent,
      List<String> attachmentIds,
      ModelTargetDto target,
      long receivedAtNanos) {}

  /** A validated, applied turn that holds the conversation's generation slot until run or aborted. */
  public static final class PreparedTurn {
    private final AiConversation conversation;
    private final AiMessage userMessage;
    private final AiMessage assistantMessage;
    private final ModelTarget target;
    private final long receivedAtNanos;
    private final AtomicBoolean released = new AtomicBoolean();

    PreparedTurn(
        AiConversation conversation,
        AiMessage userMessage,
        AiMessage assistantMessage,
        ModelTarget target,
        long receivedAtNanos) {
      this.conversation = conversation;
      this.userMessage = userMessage;
      this.assistantMessage = assistantMessage;
      this.target = target;
      this.receivedAtNanos = receivedAtNanos;
    }

    public String conversationId() {
      return conversation.getId();
    }

    public String userMessageId() {
      return userMessage.getId();
    }

    public String assistantMessageId() {
      return assistantMessage.getId();
    }
  }

  /** Mutable outcome of a run, persisted onto the assistant message in the {@code finally}. */
  private static final class Finish {
    String status = AiMessage.STATUS_ERROR;
    MessageError error = new MessageError(ChatErrorCodes.INTERNAL, "Unexpected error while generating");
    MessageGeneration generation;
    String content = "";
    String thinking;
  }

  private final AiConversationRepository conversations;
  private final AiMessageRepository messages;
  private final ConversationAccess access;
  private final GenerationRegistry generations;
  private final ModelRouter router;
  private final OllamaTurnStreamer streamer;
  private final ConversationSettingsResolver settings;
  private final AiPreferencesService preferences;
  private final ObjectProvider<MessageLifecycleHook> hooks;
  private final int maxMessageChars;

  public TurnService(
      AiConversationRepository conversations,
      AiMessageRepository messages,
      ConversationAccess access,
      GenerationRegistry generations,
      ModelRouter router,
      OllamaTurnStreamer streamer,
      ConversationSettingsResolver settings,
      AiPreferencesService preferences,
      ObjectProvider<MessageLifecycleHook> hooks,
      @Value("${ai.chat.max-message-chars:32000}") int maxMessageChars) {
    this.conversations = conversations;
    this.messages = messages;
    this.access = access;
    this.generations = generations;
    this.router = router;
    this.streamer = streamer;
    this.settings = settings;
    this.preferences = preferences;
    this.hooks = hooks;
    this.maxMessageChars = maxMessageChars;
  }

  // ---- step 1: validate and apply ----------------------------------------------------------

  /**
   * Validates the command and applies it. Throws {@link ResponseStatusException} 400, 404 or 409
   * before anything is persisted; after it returns the caller owns the generation slot and must
   * call {@link #run} or {@link #abort}.
   */
  public PreparedTurn prepare(String username, TurnCommand command) {
    AiConversation conversation = access.owned(username, command.conversationId());
    AiMessage anchor = null;
    switch (command.kind()) {
      case SEND -> validateContent(command.content());
      case REGENERATE -> {
        anchor = access.message(conversation, command.messageId());
        if (!AiMessage.ROLE_ASSISTANT.equals(anchor.getRole())) {
          throw badRequest("Only an assistant message can be regenerated");
        }
      }
      case EDIT -> {
        anchor = access.message(conversation, command.messageId());
        if (!AiMessage.ROLE_USER.equals(anchor.getRole())) {
          throw badRequest("Only a user message can be edited");
        }
        validateContent(command.content());
      }
    }
    // Part 3 hook: resolve and validate attachment ids here (unknown or foreign id -> 400).
    if (command.attachmentIds() != null && !command.attachmentIds().isEmpty()) {
      throw badRequest("Attachments are not supported yet");
    }
    ModelTarget requested = ConversationService.toTarget(command.target());
    ModelTarget effective = requested != null ? requested : conversation.getTarget();
    if (effective == null) {
      throw badRequest("No model selected: send a target or set one on the conversation");
    }
    if (!generations.tryAcquire(conversation.getId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A generation is already in flight for this conversation");
    }
    try {
      PreparedTurn turn = apply(username, conversation, command, anchor, requested, effective);
      if (requested != null) {
        preferences.setLastTarget(username, requested);
      }
      return turn;
    } catch (RuntimeException ex) {
      generations.release(conversation.getId());
      throw ex;
    }
  }

  private PreparedTurn apply(
      String username,
      AiConversation conversation,
      TurnCommand command,
      AiMessage anchor,
      ModelTarget requested,
      ModelTarget effective) {
    Instant now = Instant.now();
    List<AiMessage> existing = messages.findByConversationIdOrderBySeqAsc(conversation.getId());
    AiMessage user;
    int assistantSeq;
    int fromSeq;

    switch (command.kind()) {
      case SEND -> {
        user =
            messages.save(
                newMessage(
                    conversation,
                    conversation.getNextSeq(),
                    conversation.getActiveLeafId(),
                    AiMessage.ROLE_USER,
                    command.content(),
                    AiMessage.STATUS_COMPLETE,
                    now));
        assistantSeq = conversation.getNextSeq() + 1;
        fromSeq = Integer.MAX_VALUE; // nothing deleted
        if (existing.isEmpty()) {
          provisionalTitle(conversation, command.content());
        }
      }
      case REGENERATE -> {
        fromSeq = anchor.getSeq();
        user = lastBefore(existing, fromSeq);
        if (user == null || !AiMessage.ROLE_USER.equals(user.getRole())) {
          throw badRequest("The message to regenerate has no user message before it");
        }
        deleteFrom(conversation, existing, fromSeq);
        assistantSeq = fromSeq;
      }
      case EDIT -> {
        fromSeq = anchor.getSeq();
        user = anchor;
        deleteFrom(conversation, existing, fromSeq + 1);
        user.setContent(command.content());
        user.setEditedAt(now);
        user.setTokenEstimate(TokenEstimator.message(command.content(), 0));
        messages.save(user);
        assistantSeq = fromSeq + 1;
        if (existing.get(0).getId().equals(user.getId())) {
          provisionalTitle(conversation, command.content());
        }
        // A deletion that reaches into the summarised range invalidates the summary; an edit
        // rewrites the user message itself, so it counts from that message.
      }
      default -> throw new IllegalStateException("Unknown turn kind");
    }

    if (conversation.getSummary() != null
        && conversation.getSummary().coversThroughSeq() >= fromSeq) {
      conversation.setSummary(null);
    }
    AiMessage assistant =
        messages.save(
            newMessage(
                conversation,
                assistantSeq,
                user.getId(),
                AiMessage.ROLE_ASSISTANT,
                "",
                AiMessage.STATUS_STREAMING,
                now));
    conversation.setNextSeq(assistantSeq + 1);
    conversation.setActiveLeafId(assistant.getId());
    conversation.setMessageCount((int) messages.countByConversationId(conversation.getId()));
    conversation.setUpdatedAt(now);
    if (requested != null) {
      conversation.setTarget(requested);
    }
    conversations.save(conversation);
    logger.info(
        "Turn {} on conversation {}: user message {}, assistant message {}",
        command.kind(),
        conversation.getId(),
        user.getId(),
        assistant.getId());
    return new PreparedTurn(conversation, user, assistant, effective, command.receivedAtNanos());
  }

  /** Deletes the message at {@code fromSeq} and everything after it, telling the hooks first. */
  private void deleteFrom(AiConversation conversation, List<AiMessage> existing, int fromSeq) {
    List<AiMessage> deleted = existing.stream().filter(m -> m.getSeq() >= fromSeq).toList();
    if (deleted.isEmpty()) {
      return;
    }
    hooks.orderedStream().forEach(hook -> hook.onMessagesDeleted(conversation, deleted));
    messages.deleteByConversationIdAndSeqGreaterThanEqual(conversation.getId(), fromSeq);
  }

  private static AiMessage lastBefore(List<AiMessage> existing, int seq) {
    AiMessage found = null;
    for (AiMessage m : existing) {
      if (m.getSeq() < seq) {
        found = m;
      }
    }
    return found;
  }

  private AiMessage newMessage(
      AiConversation conversation,
      int seq,
      String parentId,
      String role,
      String content,
      String status,
      Instant now) {
    return AiMessage.builder()
        .conversationId(conversation.getId())
        .ownerUsername(conversation.getOwnerUsername())
        .seq(seq)
        .parentId(parentId)
        .role(role)
        .content(content)
        .status(status)
        .tokenEstimate(TokenEstimator.message(content, 0))
        .schemaVersion(AiMessage.SCHEMA_VERSION)
        .createdAt(now)
        .build();
  }

  private static void provisionalTitle(AiConversation conversation, String content) {
    if (!AiConversation.TITLE_DEFAULT.equals(conversation.getTitleSource())) {
      return;
    }
    String firstLine = content.strip().lines().findFirst().orElse("").strip();
    if (firstLine.isEmpty()) {
      return;
    }
    conversation.setTitle(
        firstLine.length() <= PROVISIONAL_TITLE_CHARS
            ? firstLine
            : firstLine.substring(0, PROVISIONAL_TITLE_CHARS));
  }

  private void validateContent(String content) {
    if (content == null || content.isBlank()) {
      throw badRequest("content must not be empty");
    }
    if (content.length() > maxMessageChars) {
      throw badRequest("content is too long (max " + maxMessageChars + " characters)");
    }
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /**
   * Gives the slot back without running (e.g. the response could not be opened). The assistant
   * message is stored as {@code error / INTERRUPTED}.
   */
  public void abort(PreparedTurn turn) {
    Finish finish = new Finish();
    finish.error = new MessageError(ConversationMapper.CODE_INTERRUPTED, "The generation was interrupted.");
    persistAndRelease(turn, finish);
  }

  // ---- steps 2 to 8: route, prompt, stream, finish -----------------------------------------

  /** Runs the generation; always persists the assistant message and releases the slot. */
  public void run(PreparedTurn turn, ChatEventSink sink) {
    Finish finish = new Finish();
    try {
      pipeline(turn, sink, finish);
    } catch (ClientGone gone) {
      finish.status = AiMessage.STATUS_CANCELLED;
      finish.error = null;
    } catch (RuntimeException ex) {
      logger.error("Turn on conversation {} failed unexpectedly", turn.conversationId(), ex);
      finish.status = AiMessage.STATUS_ERROR;
      finish.error = new MessageError(ChatErrorCodes.INTERNAL, "Unexpected error while generating");
      quietly(
          sink,
          "error",
          errorEvent(turn, ChatErrorCodes.INTERNAL, finish.error.message(), false, List.of()));
    } finally {
      persistAndRelease(turn, finish);
    }
  }

  /** The client went away before the engine started. */
  private static final class ClientGone extends RuntimeException {
    ClientGone() {
      super(null, null, false, false);
    }
  }

  private void pipeline(PreparedTurn turn, ChatEventSink sink, Finish finish) {
    AiConversation conversation = turn.conversation;
    ModelTarget target = turn.target;
    Set<String> tried = new HashSet<>();

    RouteOutcome first = router.route(target.model(), target.nodeId(), Set.of());
    if (first instanceof RouteOutcome.Failed failed) {
      failWith(turn, sink, finish, null, failed.code(), failed.message(), failed.retryable(), failed.alternatives());
      return;
    }
    RoutedTarget routed = ((RouteOutcome.Routed) first).target();
    RoutedTarget from = null;

    for (int attempt = 0; ; attempt++) {
      routed = settings.apply(routed, conversation);

      // Part 3 hook: if the new user message has images and !routed.hasCapability("vision"),
      // finish with MODEL_NOT_VISION here.
      List<AiMessage> stored = messages.findByConversationIdOrderBySeqAsc(conversation.getId());
      PromptPlan plan =
          ContextBuilder.build(
              stored,
              conversation.getSummary(),
              settings.systemPrompt(conversation),
              routed.contextLength(),
              routed.hasCapability("vision"));
      if (plan.tooSmall()) {
        failWith(
            turn, sink, finish, routed, ChatErrorCodes.CONTEXT_TOO_SMALL,
            "Your message is too long for this model's context window.", false, List.of());
        return;
      }

      if (from != null) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("phase", "failover");
        status.put("fromNodeId", from.nodeId());
        status.put("toNodeId", routed.nodeId());
        status.put("message", from.nodeName() + " did not answer, trying " + routed.nodeName() + "…");
        write(sink, "status", status);
      }
      write(sink, "meta", metaEvent(turn, routed, plan, conversation));

      TurnResult result = streamer.stream(turnRequest(turn, conversation, routed, plan), sink);

      if (result.ended() == Ended.ERROR
          && routed.auto()
          && !result.upstreamStarted()
          && attempt < MAX_FAILOVERS) {
        tried.add(routed.nodeId());
        RouteOutcome next = router.route(target.model(), null, tried);
        if (next instanceof RouteOutcome.Routed n) {
          logger.info(
              "Failing over conversation {} from node {} to node {}",
              conversation.getId(), routed.nodeId(), n.target().nodeId());
          from = routed;
          routed = n.target();
          continue;
        }
      }
      finishFromResult(turn, sink, finish, routed, plan, result, tried);
      return;
    }
  }

  private void finishFromResult(
      PreparedTurn turn,
      ChatEventSink sink,
      Finish finish,
      RoutedTarget routed,
      PromptPlan plan,
      TurnResult result,
      Set<String> tried) {
    finish.content = result.content();
    finish.thinking = result.thinking();
    TurnResult.TurnStats stats = result.stats();
    finish.generation =
        new MessageGeneration(
            routed.nodeId(),
            routed.nodeName(),
            routed.model(),
            routed.auto(),
            settings.presetId(turn.conversation),
            settings.presetName(turn.conversation),
            routed.contextLength(),
            result.promptTokens(),
            result.completionTokens(),
            stats.ttftMs(),
            stats.totalMs(),
            stats.loadMs(),
            stats.thinkingMs(),
            stats.tokensPerSecond(),
            result.finishReason());
    switch (result.ended()) {
      case COMPLETED -> {
        finish.status = AiMessage.STATUS_COMPLETE;
        finish.error = null;
        int promptTokens =
            result.promptTokens() != null ? result.promptTokens() : plan.promptTokensEstimate();
        int completionTokens =
            result.completionTokens() != null
                ? result.completionTokens()
                : TokenEstimator.tokens(result.content());
        Map<String, Object> statsEvent = new LinkedHashMap<>();
        statsEvent.put("ttftMs", stats.ttftMs());
        statsEvent.put("totalMs", stats.totalMs());
        statsEvent.put("loadMs", stats.loadMs());
        statsEvent.put("thinkingMs", stats.thinkingMs());
        statsEvent.put("tokensPerSecond", stats.tokensPerSecond());
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("messageId", turn.assistantMessageId());
        done.put("userMessageId", turn.userMessageId());
        done.put("promptTokens", promptTokens);
        done.put("completionTokens", completionTokens);
        done.put("contextLength", routed.contextLength());
        done.put("finishReason", result.finishReason());
        done.put("contextOverflow", promptTokens >= routed.contextLength());
        done.put("stats", statsEvent);
        quietly(sink, "done", done);
      }
      case CANCELLED -> {
        finish.status = AiMessage.STATUS_CANCELLED;
        finish.error = null;
      }
      case ERROR -> {
        TurnResult.TurnError error = result.error();
        finish.status = AiMessage.STATUS_ERROR;
        finish.error = new MessageError(error.code(), error.message());
        List<Alternative> alternatives = List.of();
        if (ChatErrorCodes.carriesAlternatives(error.code())) {
          Set<String> exclude = new HashSet<>(tried);
          exclude.add(routed.nodeId());
          alternatives = router.alternatives(routed.model(), exclude);
        }
        quietly(
            sink,
            "error",
            errorEvent(turn, error.code(), error.message(), retryable(error.code()), alternatives));
      }
    }
  }

  /** A failure before any generation: routing, context size. Stores the error, writes it. */
  private void failWith(
      PreparedTurn turn,
      ChatEventSink sink,
      Finish finish,
      RoutedTarget routed,
      String code,
      String message,
      boolean retryable,
      List<Alternative> alternatives) {
    finish.status = AiMessage.STATUS_ERROR;
    finish.error = new MessageError(code, message);
    finish.generation = null;
    quietly(sink, "error", errorEvent(turn, code, message, retryable, alternatives));
  }

  private TurnRequest turnRequest(
      PreparedTurn turn, AiConversation conversation, RoutedTarget routed, PromptPlan plan) {
    List<TurnMessage> prompt = new ArrayList<>();
    if (plan.summaryActive()) {
      prompt.add(TurnMessage.of("system", ContextBuilder.SUMMARY_PREFIX + plan.summaryUsed().text()));
    }
    for (AiMessage message : plan.messages()) {
      // Part 3 hook: attach base64 images here for messages that carry image attachments.
      prompt.add(TurnMessage.of(message.getRole(), message.getContent()));
    }
    return new TurnRequest(
        routed,
        prompt,
        settings.systemPrompt(conversation),
        routed.contextLength(),
        settings.temperature(conversation),
        routed.hasCapability("thinking"),
        turn.receivedAtNanos);
  }

  private Map<String, Object> metaEvent(
      PreparedTurn turn, RoutedTarget routed, PromptPlan plan, AiConversation conversation) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("promptTokensEstimate", plan.promptTokensEstimate());
    context.put("trimmedCount", plan.trimmedCount());
    context.put("summaryActive", plan.summaryActive());
    context.put("imagesOmitted", plan.imagesOmitted());
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("conversationId", turn.conversationId());
    meta.put("userMessageId", turn.userMessageId());
    meta.put("assistantMessageId", turn.assistantMessageId());
    meta.put("nodeId", routed.nodeId());
    meta.put("nodeName", routed.nodeName());
    meta.put("model", routed.model());
    meta.put("auto", routed.auto());
    meta.put("presetId", settings.presetId(conversation));
    meta.put("contextLength", routed.contextLength());
    meta.put("context", context);
    return meta;
  }

  private Map<String, Object> errorEvent(
      PreparedTurn turn,
      String code,
      String message,
      boolean retryable,
      List<Alternative> alternatives) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("messageId", turn.assistantMessageId());
    error.put("userMessageId", turn.userMessageId());
    error.put("code", code);
    error.put("message", message);
    error.put("retryable", retryable);
    if (ChatErrorCodes.carriesAlternatives(code)) {
      error.put(
          "alternatives",
          alternatives.stream()
              .map(
                  a -> {
                    Map<String, Object> alt = new LinkedHashMap<>();
                    alt.put("nodeId", a.nodeId());
                    alt.put("nodeName", a.nodeName());
                    alt.put("model", a.model());
                    alt.put("loaded", a.loaded());
                    alt.put("status", a.status().name());
                    return alt;
                  })
              .toList());
    }
    return error;
  }

  private static boolean retryable(String code) {
    return switch (code) {
      case ChatErrorCodes.NODE_OFFLINE,
              ChatErrorCodes.NO_NODE_AVAILABLE,
              ChatErrorCodes.NODE_UNREACHABLE,
              ChatErrorCodes.NODE_ERROR,
              ChatErrorCodes.TIMEOUT ->
          true;
      default -> false;
    };
  }

  /** A write the turn cannot continue without: a failed one means the client is gone. */
  private static void write(ChatEventSink sink, String name, Map<String, ?> data) {
    try {
      sink.event(name, data);
    } catch (UncheckedIOException gone) {
      throw new ClientGone();
    }
  }

  /** A terminal write: if the client is already gone there is nobody to tell. */
  private static void quietly(ChatEventSink sink, String name, Map<String, ?> data) {
    try {
      sink.event(name, data);
    } catch (UncheckedIOException gone) {
      // nobody left to tell
    }
  }

  private void persistAndRelease(PreparedTurn turn, Finish finish) {
    if (!turn.released.compareAndSet(false, true)) {
      return;
    }
    try {
      AiMessage assistant = turn.assistantMessage;
      assistant.setContent(finish.content);
      assistant.setThinking(finish.thinking);
      assistant.setStatus(finish.status);
      assistant.setError(finish.error);
      assistant.setGeneration(finish.generation);
      assistant.setTokenEstimate(TokenEstimator.tokens(finish.content));
      assistant.setCompletedAt(Instant.now());
      messages.save(assistant);
    } catch (RuntimeException ex) {
      logger.error("Could not persist assistant message {}", turn.assistantMessageId(), ex);
    } finally {
      generations.release(turn.conversationId());
    }
  }
}
