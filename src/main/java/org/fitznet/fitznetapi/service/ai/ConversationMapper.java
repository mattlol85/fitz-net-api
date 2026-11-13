package org.fitznet.fitznetapi.service.ai;

import java.util.List;
import org.fitznet.fitznetapi.dto.ConversationSettingsDto;
import org.fitznet.fitznetapi.dto.ModelTargetDto;
import org.fitznet.fitznetapi.dto.responses.ChatSummaryDto;
import org.fitznet.fitznetapi.dto.responses.ContextUsageDto;
import org.fitznet.fitznetapi.dto.responses.ConversationDetailDto;
import org.fitznet.fitznetapi.dto.responses.ConversationDto;
import org.fitznet.fitznetapi.dto.responses.ConversationSummaryDto;
import org.fitznet.fitznetapi.dto.responses.MessageDto;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;
import org.fitznet.fitznetapi.model.MessageError;
import org.fitznet.fitznetapi.model.MessageGeneration;
import org.fitznet.fitznetapi.model.ModelTarget;
import org.fitznet.fitznetapi.model.StoredSummary;
import org.springframework.stereotype.Component;

/** Entities to client-facing DTOs. */
@Component
public class ConversationMapper {

  public static final String CODE_INTERRUPTED = "INTERRUPTED";

  private final GenerationRegistry generations;

  public ConversationMapper(GenerationRegistry generations) {
    this.generations = generations;
  }

  public ConversationDto conversation(AiConversation c) {
    return new ConversationDto(
        c.getId(),
        c.getTitle(),
        c.getTitleSource(),
        c.isPinned(),
        target(c.getTarget()),
        settings(c),
        c.getMessageCount(),
        c.getCreatedAt(),
        c.getUpdatedAt());
  }

  public ConversationSummaryDto summaryRow(AiConversation c, String matchSnippet) {
    return new ConversationSummaryDto(
        c.getId(),
        c.getTitle(),
        c.getTitleSource(),
        c.isPinned(),
        target(c.getTarget()),
        settings(c),
        c.getMessageCount(),
        c.getCreatedAt(),
        c.getUpdatedAt(),
        matchSnippet);
  }

  public ConversationDetailDto detail(
      AiConversation c, List<AiMessage> messages, ContextUsageDto context) {
    return new ConversationDetailDto(
        c.getId(),
        c.getTitle(),
        c.getTitleSource(),
        c.isPinned(),
        target(c.getTarget()),
        settings(c),
        c.getMessageCount(),
        c.getCreatedAt(),
        c.getUpdatedAt(),
        chatSummary(c.getSummary()),
        context,
        messages.stream().map(this::message).toList());
  }

  public ChatSummaryDto chatSummary(StoredSummary summary) {
    return summary == null
        ? null
        : new ChatSummaryDto(
            summary.text(),
            summary.coversThroughSeq(),
            summary.model(),
            summary.partial(),
            summary.createdAt());
  }

  /**
   * A message as the client sees it. A {@code streaming} message with no generation in flight on
   * this API is an orphan and is reported as {@code error / INTERRUPTED}.
   */
  public MessageDto message(AiMessage m) {
    String status = m.getStatus();
    MessageError error = m.getError();
    if (AiMessage.STATUS_STREAMING.equals(status) && !generations.isActive(m.getConversationId())) {
      status = AiMessage.STATUS_ERROR;
      error = new MessageError(CODE_INTERRUPTED, "The generation was interrupted.");
    }
    MessageGeneration g = m.getGeneration();
    return new MessageDto(
        m.getId(),
        m.getSeq(),
        m.getParentId(),
        m.getRole(),
        m.getContent(),
        m.getThinking(),
        m.getAttachments() == null
            ? List.of()
            : m.getAttachments().stream()
                .map(
                    a ->
                        new MessageDto.AttachmentDto(
                            a.id(), a.kind(), a.mimeType(), a.sizeBytes(), a.name()))
                .toList(),
        List.of(),
        status,
        error == null ? null : new MessageDto.ErrorDto(error.code(), error.message()),
        g == null
            ? null
            : new MessageDto.GenerationDto(
                g.nodeId(),
                g.nodeName(),
                g.model(),
                g.auto(),
                g.presetId(),
                g.presetName(),
                g.contextLength(),
                g.promptTokens(),
                g.completionTokens(),
                g.ttftMs(),
                g.totalMs(),
                g.loadMs(),
                g.thinkingMs(),
                g.tokensPerSecond(),
                g.finishReason(),
                g.contextOverflow()),
        m.getCreatedAt(),
        m.getEditedAt());
  }

  static ModelTargetDto target(ModelTarget target) {
    return target == null ? null : new ModelTargetDto(target.model(), target.nodeId());
  }

  static ConversationSettingsDto settings(AiConversation c) {
    return new ConversationSettingsDto(c.getPresetId(), c.getSystemPrompt());
  }
}
