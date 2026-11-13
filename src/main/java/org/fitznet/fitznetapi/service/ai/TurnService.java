package org.fitznet.fitznetapi.service.ai;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fitznet.fitznetapi.dto.ModelTargetDto;
import org.fitznet.fitznetapi.model.AiAttachment;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.MessageAttachment;
import org.fitznet.fitznetapi.model.MessageError;
import org.fitznet.fitznetapi.model.MessageGeneration;
import org.fitznet.fitznetapi.model.ModelTarget;
import org.fitznet.fitznetapi.repository.AiConversationRepository;
import org.fitznet.fitznetapi.repository.AiMessageRepository;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.service.ChatEventSink;
import org.fitznet.fitznetapi.service.ai.TurnResult.Ended;
import org.slf4j.Logger;
import org.springframework.data.mongodb.core.query.Update;
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
  private final AttachmentService attachments;
  private final AiNodeRepository nodes;
  private final ConversationWrites writes;
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
      AttachmentService attachments,
      AiNodeRepository nodes,
      ConversationWrites writes,
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
    this.attachments = attachments;
    this.nodes = nodes;
    this.writes = writes;
    this.maxMessageChars = maxMessageChars;
  }

  // ---- step 1: validate and apply ----------------------------------------------------------

  /**
   * Validates the command and applies it. Throws {@link ResponseStatusException} 400, 404 or 409
   * before anything is persisted; after it returns the caller owns the generation slot and must
   * call {@link #run} or {@link #abort}.
   */
  public PreparedTurn prepare(String username, TurnCommand command) {
    AiConversation found = access.owned(username, command.conversationId()); // 404 first
    if (!generations.tryAcquire(found.getId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A generation is already in flight for this conversation");
    }
    try {
      // Everything the turn is based on is read after the slot is ours: another turn, an edit or a
      // summarise that finished a moment ago may have changed the conversation or its messages.
      AiConversation conversation = access.owned(username, command.conversationId());
      AiMessage anchor = null;
      // Attachments to attach to the user message, and whether an edit replaces the existing ones.
      List<AiAttachment> newAttachments = List.of();
      boolean replaceAttachments = false;
      switch (command.kind()) {
        case SEND -> {
          newAttachments = attachments.resolveForNewMessage(username, command.attachmentIds());
          validateContent(command.content(), !newAttachments.isEmpty());
        }
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
          replaceAttachments = command.attachmentIdsPresent();
          if (replaceAttachments) {
            newAttachments = attachments.resolveForEdit(username, command.attachmentIds(), anchor);
          }
          int resulting =
              replaceAttachments
                  ? newAttachments.size()
                  : anchor.getAttachments() == null ? 0 : anchor.getAttachments().size();
          validateContent(command.content(), resulting > 0);
        }
      }
      ModelTarget requested = ConversationService.toTarget(command.target());
      ModelTarget effective = requested != null ? requested : conversation.getTarget();
      if (effective == null) {
        throw badRequest("No model selected: send a target or set one on the conversation");
      }
      PreparedTurn turn =
          apply(
              conversation, command, anchor, requested, effective, newAttachments, replaceAttachments);
      if (requested != null) {
        preferences.setLastTarget(username, requested);
      }
      return turn;
    } catch (RuntimeException ex) {
      generations.release(found.getId());
      throw ex;
    }
  }

  private PreparedTurn apply(
      AiConversation conversation,
      TurnCommand command,
      AiMessage anchor,
      ModelTarget requested,
      ModelTarget effective,
      List<AiAttachment> newAttachments,
      boolean replaceAttachments) {
    Instant now = Instant.now();
    String content = command.content() == null ? "" : command.content();
    List<MessageAttachment> refs =
        new ArrayList<>(newAttachments.stream().map(AttachmentService::ref).toList());
    List<AiMessage> existing = messages.findByConversationIdOrderBySeqAsc(conversation.getId());
    // The stored messages are the truth: the counter on the conversation may lag behind them.
    AiMessage last = existing.isEmpty() ? null : existing.get(existing.size() - 1);
    int nextSeq = last == null ? 1 : last.getSeq() + 1;
    boolean titleChanged = false;
    AiMessage user;
    int assistantSeq;
    int fromSeq;

    switch (command.kind()) {
      case SEND -> {
        user =
            messages.save(
                newMessage(
                    conversation,
                    nextSeq,
                    last == null ? null : last.getId(),
                    AiMessage.ROLE_USER,
                    content,
                    refs,
                    AiMessage.STATUS_COMPLETE,
                    now));
        attachments.attach(newAttachments, conversation.getId(), user.getId());
        assistantSeq = nextSeq + 1;
        fromSeq = Integer.MAX_VALUE; // nothing deleted
        if (existing.isEmpty()) {
          titleChanged = provisionalTitle(conversation, content);
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
        user.setContent(content);
        user.setEditedAt(now);
        if (replaceAttachments) {
          // Dropped images are deleted; kept ones stay attached; new ones are attached below.
          Set<String> kept = refs.stream().map(MessageAttachment::id).collect(Collectors.toSet());
          if (user.getAttachments() != null) {
            attachments.deleteAll(
                user.getAttachments().stream()
                    .map(MessageAttachment::id)
                    .filter(id -> !kept.contains(id))
                    .toList());
          }
          user.setAttachments(refs);
          attachments.attach(newAttachments, conversation.getId(), user.getId());
        }
        user.setTokenEstimate(
            TokenEstimator.message(
                content, user.getAttachments() == null ? 0 : user.getAttachments().size()));
        messages.save(user);
        assistantSeq = fromSeq + 1;
        if (existing.get(0).getId().equals(user.getId())) {
          titleChanged = provisionalTitle(conversation, content);
        }
        // A deletion that reaches into the summarised range invalidates the summary; an edit
        // rewrites the user message itself, so it counts from that message.
      }
      default -> throw new IllegalStateException("Unknown turn kind");
    }

    boolean summaryDropped = false;
    if (conversation.getSummary() != null
        && conversation.getSummary().coversThroughSeq() >= fromSeq) {
      conversation.setSummary(null);
      summaryDropped = true;
    }
    AiMessage assistant =
        messages.save(
            newMessage(
                conversation,
                assistantSeq,
                user.getId(),
                AiMessage.ROLE_ASSISTANT,
                "",
                List.of(),
                AiMessage.STATUS_STREAMING,
                now));
    conversation.setNextSeq(assistantSeq + 1);
    conversation.setActiveLeafId(assistant.getId());
    conversation.setMessageCount((int) messages.countByConversationId(conversation.getId()));
    conversation.setUpdatedAt(now);
    if (requested != null) {
      conversation.setTarget(requested);
    }
    // Targeted write: only what the turn owns, so a PATCH that landed meanwhile is not overwritten.
    Update update =
        new Update()
            .set("nextSeq", conversation.getNextSeq())
            .set("activeLeafId", conversation.getActiveLeafId())
            .set("messageCount", conversation.getMessageCount())
            .set("updatedAt", now);
    if (summaryDropped) {
      update.unset("summary");
    }
    if (requested != null) {
      update.set("target", requested);
    }
    writes.apply(conversation.getId(), update);
    if (titleChanged) {
      writes.applyProvisionalTitle(conversation.getId(), conversation.getTitle());
    }
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
      List<MessageAttachment> refs,
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
        .attachments(new ArrayList<>(refs))
        .tokenEstimate(TokenEstimator.message(content, refs.size()))
        .schemaVersion(AiMessage.SCHEMA_VERSION)
        .createdAt(now)
        .build();
  }

  /** Sets the in-memory title from the first line; returns whether it changed. */
  private static boolean provisionalTitle(AiConversation conversation, String content) {
    if (!AiConversation.TITLE_DEFAULT.equals(conversation.getTitleSource())) {
      return false;
    }
    String firstLine = content.strip().lines().findFirst().orElse("").strip();
    if (firstLine.isEmpty()) {
      return false;
    }
    conversation.setTitle(
        firstLine.length() <= PROVISIONAL_TITLE_CHARS
            ? firstLine
            : firstLine.substring(0, PROVISIONAL_TITLE_CHARS));
    return true;
  }

  /** Content may be empty only when the message carries at least one image. */
  private void validateContent(String content, boolean hasAttachments) {
    if ((content == null || content.isBlank()) && !hasAttachments) {
      throw badRequest("content must not be empty");
    }
    if (content != null && content.length() > maxMessageChars) {
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
    // Read at generation time: a preset edited or deleted since the last turn applies right now.
    EffectiveSettings effective = settings.resolve(conversation);

    RouteOutcome first = router.route(target.model(), target.nodeId(), Set.of());
    if (first instanceof RouteOutcome.Failed failed) {
      failWith(turn, sink, finish, failed.code(), failed.message(), failed.retryable(), failed.alternatives());
      return;
    }
    RoutedTarget routed = ((RouteOutcome.Routed) first).target();
    RoutedTarget from = null;
    // Read once per turn and reused across failover attempts; so is every image's base64.
    List<AiMessage> stored = access.history(conversation.getId());
    Map<String, String> imageCache = new HashMap<>();

    for (int attempt = 0; ; attempt++) {
      routed = effective.apply(routed);

      boolean newMessageHasImages =
          turn.userMessage.getAttachments() != null && !turn.userMessage.getAttachments().isEmpty();
      if (newMessageHasImages && !routed.hasCapability("vision")) {
        failWith(
            turn, sink, finish, ChatErrorCodes.MODEL_NOT_VISION,
            routed.model() + " cannot see images. Pick a vision model or remove the image.",
            false, List.of());
        return;
      }
      PromptPlan plan =
          ContextBuilder.build(
              stored,
              conversation.getSummary(),
              effective.systemPrompt(),
              routed.contextLength(),
              routed.hasCapability("vision"));
      if (plan.tooSmall()) {
        failWith(
            turn, sink, finish, ChatErrorCodes.CONTEXT_TOO_SMALL,
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
      write(sink, "meta", metaEvent(turn, routed, plan, effective));

      TurnResult result = streamer.stream(turnRequest(turn, effective, routed, plan, imageCache), sink);

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
      finishFromResult(turn, sink, finish, effective, routed, plan, result, tried);
      return;
    }
  }

  private void finishFromResult(
      PreparedTurn turn,
      ChatEventSink sink,
      Finish finish,
      EffectiveSettings effective,
      RoutedTarget routed,
      PromptPlan plan,
      TurnResult result,
      Set<String> tried) {
    finish.content = result.content();
    finish.thinking = result.thinking();
    TurnResult.TurnStats stats = result.stats();
    int promptTokens =
        result.promptTokens() != null ? result.promptTokens() : plan.promptTokensEstimate();
    Boolean contextOverflow =
        result.ended() == Ended.COMPLETED ? promptTokens >= routed.contextLength() : null;
    finish.generation =
        new MessageGeneration(
            routed.nodeId(),
            routed.nodeName(),
            routed.model(),
            routed.auto(),
            effective.presetId(),
            effective.presetName(),
            routed.contextLength(),
            result.promptTokens(),
            result.completionTokens(),
            stats.ttftMs(),
            stats.totalMs(),
            stats.loadMs(),
            stats.thinkingMs(),
            stats.tokensPerSecond(),
            result.finishReason(),
            contextOverflow);
    switch (result.ended()) {
      case COMPLETED -> {
        finish.status = AiMessage.STATUS_COMPLETE;
        finish.error = null;
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
        done.put("contextOverflow", contextOverflow);
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

  /**
   * A failure before any generation (routing, vision check, context size). Stores the error with a
   * minimal generation block (the requested model and node, so the client can offer alternatives
   * after a reload) and writes the error event.
   */
  private void failWith(
      PreparedTurn turn,
      ChatEventSink sink,
      Finish finish,
      String code,
      String message,
      boolean retryable,
      List<Alternative> alternatives) {
    finish.status = AiMessage.STATUS_ERROR;
    finish.error = new MessageError(code, message);
    finish.generation = minimalGeneration(turn.target);
    quietly(sink, "error", errorEvent(turn, code, message, retryable, alternatives));
  }

  /** The requested model and node (null node = Auto) and {@code finishReason: error}; nothing else. */
  private MessageGeneration minimalGeneration(ModelTarget requested) {
    String nodeName =
        requested.nodeId() == null
            ? null
            : nodes.findById(requested.nodeId()).map(AiNode::getName).orElse(null);
    return new MessageGeneration(
        requested.nodeId(),
        nodeName,
        requested.model(),
        requested.nodeId() == null,
        null, null, null, null, null, null, null, null, null, null,
        "error",
        null);
  }

  private TurnRequest turnRequest(
      PreparedTurn turn,
      EffectiveSettings effective,
      RoutedTarget routed,
      PromptPlan plan,
      Map<String, String> imageCache) {
    boolean vision = routed.hasCapability("vision");
    List<TurnMessage> prompt = new ArrayList<>();
    if (plan.summaryActive()) {
      prompt.add(TurnMessage.of("system", ContextBuilder.SUMMARY_PREFIX + plan.summaryUsed().text()));
    }
    // The new user message always carries its images (a model without vision was rejected before
    // this point); older messages only for a vision model, else they are omitted.
    Set<String> wanted = new HashSet<>();
    for (AiMessage message : plan.messages()) {
      if ((vision || message.getId().equals(turn.userMessageId())) && message.getAttachments() != null) {
        message.getAttachments().forEach(a -> wanted.add(a.id()));
      }
    }
    wanted.removeAll(imageCache.keySet());
    if (!wanted.isEmpty()) {
      imageCache.putAll(attachments.loadImages(wanted));
    }
    for (AiMessage message : plan.messages()) {
      boolean sendImages = vision || message.getId().equals(turn.userMessageId());
      List<String> images = new ArrayList<>();
      if (sendImages && message.getAttachments() != null) {
        for (MessageAttachment ref : message.getAttachments()) {
          String encoded = imageCache.get(ref.id());
          if (encoded != null) {
            images.add(encoded);
          }
        }
      }
      prompt.add(new TurnMessage(message.getRole(), message.getContent(), images));
    }
    return new TurnRequest(
        routed,
        prompt,
        effective.systemPrompt(),
        routed.contextLength(),
        effective.temperature(),
        routed.hasCapability("thinking"),
        turn.receivedAtNanos);
  }

  private Map<String, Object> metaEvent(
      PreparedTurn turn, RoutedTarget routed, PromptPlan plan, EffectiveSettings effective) {
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
    meta.put("presetId", effective.presetId());
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
