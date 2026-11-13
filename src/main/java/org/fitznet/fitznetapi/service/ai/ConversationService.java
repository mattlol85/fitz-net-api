package org.fitznet.fitznetapi.service.ai;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.fitznet.fitznetapi.dto.ConversationSettingsDto;
import org.fitznet.fitznetapi.dto.ModelTargetDto;
import org.fitznet.fitznetapi.dto.requests.CreateConversationRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateConversationRequestDto;
import org.fitznet.fitznetapi.dto.responses.ConversationDetailDto;
import org.fitznet.fitznetapi.dto.responses.ConversationDto;
import org.fitznet.fitznetapi.dto.responses.ConversationListDto;
import org.fitznet.fitznetapi.dto.responses.ConversationSummaryDto;
import org.fitznet.fitznetapi.dto.responses.DeleteResponseDto;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;
import org.fitznet.fitznetapi.model.ModelTarget;
import org.fitznet.fitznetapi.repository.AiConversationRepository;
import org.fitznet.fitznetapi.repository.AiMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Conversation CRUD, search and titles (contract section 6). Every method takes the owner's
 * canonical username; a conversation owned by someone else is a 404. Titles, summaries and message
 * content are never logged.
 */
@Service
public class ConversationService {

  private static final Logger logger = LoggerFactory.getLogger(ConversationService.class);

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;
  static final int SNIPPET_CHARS = 160;
  static final int TITLE_MAX_CHARS = 80;
  static final Duration TITLE_TIMEOUT = Duration.ofSeconds(20);
  static final int TITLE_NUM_PREDICT = 32;
  private static final Pattern TITLE_PREFIX = Pattern.compile("^(?i:title)\\s*:\\s*");

  static final String TITLE_INSTRUCTIONS =
      "Write a title of at most 6 words for the conversation below. Reply with the title only:"
          + " no quotes, no trailing punctuation.";

  private final AiConversationRepository conversations;
  private final AiMessageRepository messages;
  private final ConversationAccess access;
  private final ConversationMapper mapper;
  private final ContextService contextService;
  private final AiPreferencesService preferences;
  private final ModelRouter router;
  private final OllamaChatCompleter completer;
  private final ConversationSettingsResolver settings;
  private final GenerationRegistry generations;
  private final ObjectProvider<MessageLifecycleHook> hooks;

  public ConversationService(
      AiConversationRepository conversations,
      AiMessageRepository messages,
      ConversationAccess access,
      ConversationMapper mapper,
      ContextService contextService,
      AiPreferencesService preferences,
      ModelRouter router,
      OllamaChatCompleter completer,
      ConversationSettingsResolver settings,
      GenerationRegistry generations,
      ObjectProvider<MessageLifecycleHook> hooks) {
    this.conversations = conversations;
    this.messages = messages;
    this.access = access;
    this.mapper = mapper;
    this.contextService = contextService;
    this.preferences = preferences;
    this.router = router;
    this.completer = completer;
    this.settings = settings;
    this.generations = generations;
    this.hooks = hooks;
  }

  public ConversationDto create(String username, CreateConversationRequestDto request) {
    Instant now = Instant.now();
    boolean userTitle = request.getTitle() != null && !request.getTitle().isBlank();
    ConversationSettingsDto requested = request.getSettings();
    AiConversation conversation =
        AiConversation.builder()
            .ownerUsername(username)
            .title(userTitle ? request.getTitle().trim() : AiConversation.DEFAULT_TITLE)
            .titleSource(userTitle ? AiConversation.TITLE_USER : AiConversation.TITLE_DEFAULT)
            .target(toTarget(request.getTarget()))
            .presetId(requested == null ? null : blankToNull(requested.getPresetId()))
            .systemPrompt(requested == null ? null : blankToNull(requested.getSystemPrompt()))
            .nextSeq(1)
            .messageCount(0)
            .schemaVersion(AiConversation.SCHEMA_VERSION)
            .createdAt(now)
            .updatedAt(now)
            .build();
    // Part 3 hook: a presetId without a target copies the preset's pinned model into the target.
    AiConversation saved = conversations.save(conversation);
    if (saved.getTarget() != null) {
      preferences.setLastTarget(username, saved.getTarget());
    }
    logger.info("Created conversation {}", saved.getId());
    return mapper.conversation(saved);
  }

  public ConversationListDto list(String username, String q, Integer limit, Integer offset) {
    int pageSize = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    int skip = offset == null || offset < 0 ? 0 : offset;
    String term = q == null || q.isBlank() ? null : q.trim();
    List<AiConversation> found = conversations.search(username, term, pageSize + 1, skip);
    boolean hasMore = found.size() > pageSize;
    List<ConversationSummaryDto> items = new ArrayList<>();
    for (AiConversation conversation : found.subList(0, Math.min(found.size(), pageSize))) {
      String snippet =
          term == null
              ? null
              : conversations
                  .firstMatchingContent(conversation.getId(), term)
                  .map(content -> snippet(content, term))
                  .orElse(null);
      items.add(mapper.summaryRow(conversation, snippet));
    }
    return new ConversationListDto(items, hasMore);
  }

  public ConversationDetailDto get(String username, String conversationId) {
    AiConversation conversation = access.owned(username, conversationId);
    List<AiMessage> stored = messages.findByConversationIdOrderBySeqAsc(conversation.getId());
    return mapper.detail(conversation, stored, contextService.usageOrNull(conversation, stored));
  }

  /** PATCH: absent fields are untouched; {@code target} and {@code settings} are replaced whole. */
  public ConversationDto update(
      String username, String conversationId, UpdateConversationRequestDto request) {
    AiConversation conversation = access.owned(username, conversationId);
    Instant now = Instant.now();
    boolean touched = false;

    if (request.getTitle() != null) {
      String title = request.getTitle().trim();
      if (title.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be blank");
      }
      conversation.setTitle(title);
      conversation.setTitleSource(AiConversation.TITLE_USER);
      touched = true;
    }
    if (request.getPinned() != null) {
      conversation.setPinned(request.getPinned()); // pinning does not move updatedAt
    }
    ModelTarget newTarget = null;
    if (request.isTargetPresent()) {
      newTarget = toTarget(request.getTarget());
      conversation.setTarget(newTarget);
    }
    if (request.isSettingsPresent()) {
      ConversationSettingsDto s = request.getSettings();
      conversation.setPresetId(s == null ? null : blankToNull(s.getPresetId()));
      conversation.setSystemPrompt(s == null ? null : blankToNull(s.getSystemPrompt()));
      touched = true;
      // Part 3 hook: presetId without a target in the same request copies the preset's pinned model.
    }
    if (touched) {
      conversation.setUpdatedAt(now);
    }
    AiConversation saved = conversations.save(conversation);
    if (newTarget != null) {
      preferences.setLastTarget(username, newTarget);
    }
    return mapper.conversation(saved);
  }

  /** Deletes the conversation and its messages. 409 while a generation is in flight. */
  public DeleteResponseDto delete(String username, String conversationId) {
    AiConversation conversation = access.owned(username, conversationId);
    if (generations.isActive(conversation.getId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "A generation is in flight for this conversation");
    }
    List<AiMessage> stored = messages.findByConversationIdOrderBySeqAsc(conversation.getId());
    hooks.orderedStream().forEach(hook -> hook.onConversationDeleted(conversation, stored));
    messages.deleteByConversationId(conversation.getId());
    conversations.deleteById(conversation.getId());
    logger.info("Deleted conversation {} with {} messages", conversation.getId(), stored.size());
    return new DeleteResponseDto(true);
  }

  /**
   * Best-effort title from the first exchange, asked of the node and model of the last assistant
   * message. On any failure, or when the title is user-set, the conversation is returned unchanged.
   */
  public ConversationDto generateTitle(String username, String conversationId) {
    AiConversation conversation = access.owned(username, conversationId);
    if (AiConversation.TITLE_USER.equals(conversation.getTitleSource())) {
      return mapper.conversation(conversation);
    }
    try {
      Optional<String> title = askForTitle(conversation);
      if (title.isPresent()) {
        conversation.setTitle(title.get());
        conversation.setTitleSource(AiConversation.TITLE_AUTO);
        conversation = conversations.save(conversation);
      }
    } catch (IOException | RuntimeException ex) {
      logger.info("Title generation skipped for conversation {}: {}", conversationId, ex.toString());
    }
    return mapper.conversation(conversation);
  }

  private Optional<String> askForTitle(AiConversation conversation) throws IOException {
    List<AiMessage> stored = messages.findByConversationIdOrderBySeqAsc(conversation.getId());
    AiMessage lastAssistant = null;
    AiMessage firstUser = null;
    AiMessage firstReply = null;
    for (AiMessage m : stored) {
      if (AiMessage.ROLE_USER.equals(m.getRole()) && firstUser == null) {
        firstUser = m;
      } else if (AiMessage.ROLE_ASSISTANT.equals(m.getRole())
          && m.getContent() != null
          && !m.getContent().isBlank()
          && m.getGeneration() != null) {
        lastAssistant = m;
        if (firstUser != null && firstReply == null) {
          firstReply = m;
        }
      }
    }
    if (lastAssistant == null || firstUser == null || firstReply == null) {
      return Optional.empty();
    }
    RouteOutcome outcome =
        router.route(
            lastAssistant.getGeneration().model(), lastAssistant.getGeneration().nodeId(), Set.of());
    if (!(outcome instanceof RouteOutcome.Routed routed)) {
      return Optional.empty();
    }
    RoutedTarget target = settings.apply(routed.target(), conversation);
    String transcript =
        "User: " + clip(firstUser.getContent(), 600) + "\nAssistant: " + clip(firstReply.getContent(), 600);
    String reply =
        completer.complete(
            target,
            TITLE_INSTRUCTIONS,
            List.of(TurnMessage.of(AiMessage.ROLE_USER, transcript)),
            target.contextLength(),
            TITLE_NUM_PREDICT,
            TITLE_TIMEOUT,
            target.hasCapability("thinking"));
    return Optional.ofNullable(cleanTitle(reply));
  }

  // ---- helpers ---------------------------------------------------------------------------

  /** First line, quotes and a "Title:" prefix stripped, at most 80 characters; null if empty. */
  static String cleanTitle(String raw) {
    if (raw == null) {
      return null;
    }
    String title = raw.strip();
    int newline = title.indexOf('\n');
    if (newline >= 0) {
      title = title.substring(0, newline).strip();
    }
    String wrapping = "^[\"'`*#\\s]+|[\"'`*\\s]+$";
    title = title.replaceAll(wrapping, "").strip();
    title = TITLE_PREFIX.matcher(title).replaceFirst("");
    title = title.replaceAll(wrapping, "").strip();
    if (title.isEmpty()) {
      return null;
    }
    return clip(title, TITLE_MAX_CHARS);
  }

  /** A window of at most 160 characters around the first case-insensitive match of {@code term}. */
  static String snippet(String content, String term) {
    String flat = content.replaceAll("\\s+", " ").strip();
    int at = -1;
    for (int i = 0; i + term.length() <= flat.length(); i++) {
      if (flat.regionMatches(true, i, term, 0, term.length())) {
        at = i;
        break;
      }
    }
    if (at < 0 || flat.length() <= SNIPPET_CHARS) {
      return clip(flat, SNIPPET_CHARS);
    }
    int start = Math.max(0, at - Math.max(0, (SNIPPET_CHARS - term.length()) / 2));
    int end = Math.min(flat.length(), start + SNIPPET_CHARS);
    start = Math.max(0, end - SNIPPET_CHARS);
    return flat.substring(start, end);
  }

  private static String clip(String text, int maxChars) {
    return text.length() <= maxChars ? text : text.substring(0, maxChars);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  static ModelTarget toTarget(ModelTargetDto dto) {
    return dto == null ? null : new ModelTarget(dto.getModel(), blankToNull(dto.getNodeId()));
  }
}
