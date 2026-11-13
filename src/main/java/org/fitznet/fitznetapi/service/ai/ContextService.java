package org.fitznet.fitznetapi.service.ai;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.fitznet.fitznetapi.dto.responses.ContextUsageDto;
import org.fitznet.fitznetapi.dto.responses.SummarizeResponseDto;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;
import org.fitznet.fitznetapi.model.ModelTarget;
import org.fitznet.fitznetapi.model.StoredSummary;
import org.fitznet.fitznetapi.repository.AiConversationRepository;
import org.fitznet.fitznetapi.repository.AiMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Context-window features of a conversation (contract section 8): the preflight usage for a target
 * and the explicit "Summarise older turns" action. The prompt itself is built by
 * {@link ContextBuilder}.
 */
@Service
public class ContextService {

  private static final Logger logger = LoggerFactory.getLogger(ContextService.class);

  static final String SUMMARY_INSTRUCTIONS =
      "You compress conversation history. Summarise the conversation below so it can replace the"
          + " original turns: keep facts, decisions, names, numbers, code identifiers and open"
          + " questions. Be concise. Reply with the summary only.";
  static final int SUMMARY_NUM_PREDICT = 600;

  private final ConversationAccess access;
  private final AiConversationRepository conversations;
  private final AiMessageRepository messages;
  private final ModelRouter router;
  private final OllamaChatCompleter completer;
  private final ConversationMapper mapper;
  private final ConversationSettingsResolver settings;
  private final GenerationRegistry generations;
  private final Duration summaryTimeout;

  public ContextService(
      ConversationAccess access,
      AiConversationRepository conversations,
      AiMessageRepository messages,
      ModelRouter router,
      OllamaChatCompleter completer,
      ConversationMapper mapper,
      ConversationSettingsResolver settings,
      GenerationRegistry generations,
      @Value("${ollama.read-timeout:180s}") Duration summaryTimeout) {
    this.access = access;
    this.conversations = conversations;
    this.messages = messages;
    this.router = router;
    this.completer = completer;
    this.mapper = mapper;
    this.settings = settings;
    this.generations = generations;
    this.summaryTimeout = summaryTimeout;
  }

  /** Usage for the conversation's own target, or null when it cannot be resolved right now. */
  public ContextUsageDto usageOrNull(AiConversation conversation, List<AiMessage> stored) {
    ModelTarget target = conversation.getTarget();
    if (target == null) {
      return null;
    }
    RouteOutcome outcome = router.route(target.model(), target.nodeId(), Set.of());
    if (!(outcome instanceof RouteOutcome.Routed routed)) {
      return null;
    }
    return usage(conversation, stored, routed.target());
  }

  /**
   * {@code GET …/context}: without {@code model} the conversation's target is used. Answers 503
   * when the target cannot be resolved (none, node offline, model gone).
   */
  public ContextUsageDto preflight(String username, String conversationId, String model, String nodeId) {
    AiConversation conversation = access.owned(username, conversationId);
    String targetModel = model;
    String targetNode = nodeId;
    if (targetModel == null || targetModel.isBlank()) {
      ModelTarget target = conversation.getTarget();
      if (target == null) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "The conversation has no model selected");
      }
      targetModel = target.model();
      targetNode = target.nodeId();
    }
    RoutedTarget routed = routeOr503(targetModel, targetNode);
    return usage(
        conversation, messages.findByConversationIdOrderBySeqAsc(conversation.getId()), routed);
  }

  /**
   * {@code POST …/summarize}: asks the conversation's current target to summarise the messages that
   * are currently trimmed, folding in any earlier summary. If that range does not fit the window
   * either, the most recent part of it is summarised and {@code partial} is set.
   */
  public SummarizeResponseDto summarize(String username, String conversationId) {
    AiConversation conversation = access.owned(username, conversationId);
    if (generations.isActive(conversation.getId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A generation is already in flight for this conversation");
    }
    ModelTarget target = conversation.getTarget();
    if (target == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The conversation has no model selected");
    }
    RoutedTarget routed = routeOr503(target.model(), target.nodeId());
    List<AiMessage> stored = messages.findByConversationIdOrderBySeqAsc(conversation.getId());
    PromptPlan plan = plan(conversation, stored, routed);
    List<AiMessage> trimmed = plan.trimmed();
    if (trimmed.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Nothing is trimmed, so there is nothing to summarise");
    }

    int window = settings.resolve(conversation).apply(routed).contextLength();
    int available =
        window - SUMMARY_NUM_PREDICT - TokenEstimator.tokens(SUMMARY_INSTRUCTIONS) - 16;
    Transcript transcript = transcript(conversation.getSummary(), trimmed, Math.max(available, 64));

    String text;
    try {
      text =
          completer
              .complete(
                  routed,
                  SUMMARY_INSTRUCTIONS,
                  List.of(TurnMessage.of(AiMessage.ROLE_USER, transcript.text())),
                  window,
                  SUMMARY_NUM_PREDICT,
                  summaryTimeout,
                  routed.hasCapability("thinking"))
              .trim();
    } catch (IOException ex) {
      logger.warn("Summarise failed on node {}: {}", routed.nodeId(), ex.getMessage());
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Could not summarise: " + routed.nodeName() + " did not answer");
    }
    if (text.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The model returned an empty summary");
    }

    int coversThrough = trimmed.get(trimmed.size() - 1).getSeq();
    StoredSummary summary =
        new StoredSummary(
            text,
            coversThrough,
            TokenEstimator.tokens(ContextBuilder.SUMMARY_PREFIX + text),
            routed.model(),
            routed.nodeId(),
            transcript.partial(),
            Instant.now());
    conversation.setSummary(summary);
    conversations.save(conversation);
    logger.info(
        "Summarised conversation {} through seq {} (partial={}) on node {}",
        conversation.getId(),
        coversThrough,
        summary.partial(),
        routed.nodeId());
    return new SummarizeResponseDto(
        mapper.conversation(conversation),
        mapper.chatSummary(summary),
        usage(conversation, stored, routed));
  }

  /** The prompt plan for the conversation on {@code routed}. */
  PromptPlan plan(AiConversation conversation, List<AiMessage> stored, RoutedTarget routed) {
    EffectiveSettings effective = settings.resolve(conversation);
    RoutedTarget target = effective.apply(routed);
    return ContextBuilder.build(
        stored,
        conversation.getSummary(),
        effective.systemPrompt(),
        target.contextLength(),
        target.hasCapability("vision"));
  }

  ContextUsageDto usage(AiConversation conversation, List<AiMessage> stored, RoutedTarget routed) {
    PromptPlan plan = plan(conversation, stored, routed);
    return new ContextUsageDto(
        routed.model(),
        routed.nodeId(),
        settings.resolve(conversation).apply(routed).contextLength(),
        plan.promptTokensEstimate(),
        plan.totalTokens(),
        plan.fits(),
        plan.trimmedCount(),
        plan.summaryActive());
  }

  private RoutedTarget routeOr503(String model, String nodeId) {
    RouteOutcome outcome = router.route(model, nodeId, Set.of());
    if (outcome instanceof RouteOutcome.Failed failed) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, failed.message());
    }
    return ((RouteOutcome.Routed) outcome).target();
  }

  private record Transcript(String text, boolean partial) {}

  /**
   * The text to summarise: the earlier summary (if it fits) followed by the trimmed messages,
   * keeping the newest ones that fit {@code availableTokens}.
   */
  private static Transcript transcript(
      StoredSummary previous, List<AiMessage> trimmed, int availableTokens) {
    int remaining = availableTokens;
    List<String> lines = new ArrayList<>();
    boolean partial = false;
    for (int i = trimmed.size() - 1; i >= 0; i--) {
      AiMessage message = trimmed.get(i);
      String speaker = AiMessage.ROLE_USER.equals(message.getRole()) ? "User: " : "Assistant: ";
      String line = speaker + message.getContent();
      int cost = TokenEstimator.tokens(line);
      if (cost > remaining) {
        if (lines.isEmpty()) {
          line = truncate(line, Math.max(remaining - 4, 1) * 3);
          lines.add(0, line);
          remaining = 0;
        }
        partial = true;
        break;
      }
      lines.add(0, line);
      remaining -= cost;
    }
    StringBuilder text = new StringBuilder();
    if (previous != null) {
      int cost = TokenEstimator.tokens(previous.text());
      if (cost <= remaining) {
        text.append("Earlier summary:\n").append(previous.text()).append("\n\n");
      } else {
        partial = true;
      }
    }
    text.append("Conversation:\n").append(String.join("\n", lines));
    return new Transcript(text.toString(), partial);
  }

  private static String truncate(String text, int maxCodePoints) {
    if (text.codePointCount(0, text.length()) <= maxCodePoints) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
  }
}
