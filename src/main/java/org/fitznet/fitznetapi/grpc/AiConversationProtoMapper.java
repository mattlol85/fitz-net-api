package org.fitznet.fitznetapi.grpc;

import static org.fitznet.fitznetapi.grpc.ProtoMapper.ifPresent;

import java.util.List;
import org.fitznet.fitznetapi.dto.ConversationSettingsDto;
import org.fitznet.fitznetapi.dto.ModelTargetDto;
import org.fitznet.fitznetapi.dto.requests.CreateConversationRequestDto;
import org.fitznet.fitznetapi.dto.requests.EditMessageRequestDto;
import org.fitznet.fitznetapi.dto.requests.RegenerateMessageRequestDto;
import org.fitznet.fitznetapi.dto.requests.SendMessageRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateConversationRequestDto;
import org.fitznet.fitznetapi.dto.responses.ChatSummaryDto;
import org.fitznet.fitznetapi.dto.responses.ContextUsageDto;
import org.fitznet.fitznetapi.dto.responses.ConversationDetailDto;
import org.fitznet.fitznetapi.dto.responses.ConversationDto;
import org.fitznet.fitznetapi.dto.responses.ConversationListDto;
import org.fitznet.fitznetapi.dto.responses.ConversationSummaryDto;
import org.fitznet.fitznetapi.dto.responses.DeleteResponseDto;
import org.fitznet.fitznetapi.dto.responses.MessageDto;
import org.fitznet.fitznetapi.dto.responses.SummarizeResponseDto;
import org.fitznet.fitznetapi.grpc.proto.ChatSummary;
import org.fitznet.fitznetapi.grpc.proto.ContextUsage;
import org.fitznet.fitznetapi.grpc.proto.Conversation;
import org.fitznet.fitznetapi.grpc.proto.ConversationDetail;
import org.fitznet.fitznetapi.grpc.proto.ConversationMessage;
import org.fitznet.fitznetapi.grpc.proto.ConversationSettings;
import org.fitznet.fitznetapi.grpc.proto.ConversationSummary;
import org.fitznet.fitznetapi.grpc.proto.CreateConversationRequest;
import org.fitznet.fitznetapi.grpc.proto.DeleteResponse;
import org.fitznet.fitznetapi.grpc.proto.EditMessageRequest;
import org.fitznet.fitznetapi.grpc.proto.ListConversationsResponse;
import org.fitznet.fitznetapi.grpc.proto.MessageAttachment;
import org.fitznet.fitznetapi.grpc.proto.MessageError;
import org.fitznet.fitznetapi.grpc.proto.MessageGeneration;
import org.fitznet.fitznetapi.grpc.proto.ModelTarget;
import org.fitznet.fitznetapi.grpc.proto.RegenerateMessageRequest;
import org.fitznet.fitznetapi.grpc.proto.SendMessageRequest;
import org.fitznet.fitznetapi.grpc.proto.SummarizeConversationResponse;
import org.fitznet.fitznetapi.grpc.proto.UpdateConversationRequest;

/** Conversions between the conversation and turn REST DTOs and their {@code ai_chat.proto} messages. */
final class AiConversationProtoMapper {

  private AiConversationProtoMapper() {}

  // ---- responses ---------------------------------------------------------------------------

  static Conversation toProto(ConversationDto dto) {
    Conversation.Builder b =
        Conversation.newBuilder()
            .setPinned(dto.pinned())
            .setMessageCount(dto.messageCount())
            .setSettings(settings(dto.settings()));
    ifPresent(dto.id(), b::setId);
    ifPresent(dto.title(), b::setTitle);
    ifPresent(dto.titleSource(), b::setTitleSource);
    ifPresent(dto.target(), t -> b.setTarget(AiChatProtoMapper.toProto(t)));
    ifPresent(dto.createdAt(), at -> b.setCreatedAt(ProtoMapper.toProto(at)));
    ifPresent(dto.updatedAt(), at -> b.setUpdatedAt(ProtoMapper.toProto(at)));
    return b.build();
  }

  static ConversationSummary toProto(ConversationSummaryDto dto) {
    ConversationSummary.Builder b =
        ConversationSummary.newBuilder()
            .setPinned(dto.pinned())
            .setMessageCount(dto.messageCount())
            .setSettings(settings(dto.settings()));
    ifPresent(dto.id(), b::setId);
    ifPresent(dto.title(), b::setTitle);
    ifPresent(dto.titleSource(), b::setTitleSource);
    ifPresent(dto.target(), t -> b.setTarget(AiChatProtoMapper.toProto(t)));
    ifPresent(dto.createdAt(), at -> b.setCreatedAt(ProtoMapper.toProto(at)));
    ifPresent(dto.updatedAt(), at -> b.setUpdatedAt(ProtoMapper.toProto(at)));
    ifPresent(dto.matchSnippet(), b::setMatchSnippet);
    return b.build();
  }

  static ListConversationsResponse toProto(ConversationListDto dto) {
    ListConversationsResponse.Builder b =
        ListConversationsResponse.newBuilder().setHasMore(dto.hasMore());
    dto.items().forEach(item -> b.addItems(toProto(item)));
    return b.build();
  }

  static ConversationDetail toProto(ConversationDetailDto dto) {
    ConversationDetail.Builder b =
        ConversationDetail.newBuilder()
            .setPinned(dto.pinned())
            .setMessageCount(dto.messageCount())
            .setSettings(settings(dto.settings()));
    ifPresent(dto.id(), b::setId);
    ifPresent(dto.title(), b::setTitle);
    ifPresent(dto.titleSource(), b::setTitleSource);
    ifPresent(dto.target(), t -> b.setTarget(AiChatProtoMapper.toProto(t)));
    ifPresent(dto.createdAt(), at -> b.setCreatedAt(ProtoMapper.toProto(at)));
    ifPresent(dto.updatedAt(), at -> b.setUpdatedAt(ProtoMapper.toProto(at)));
    ifPresent(dto.summary(), s -> b.setSummary(toProto(s)));
    ifPresent(dto.context(), c -> b.setContext(toProto(c)));
    dto.messages().forEach(m -> b.addMessages(toProto(m)));
    return b.build();
  }

  static ChatSummary toProto(ChatSummaryDto dto) {
    ChatSummary.Builder b =
        ChatSummary.newBuilder()
            .setCoversThroughSeq(dto.coversThroughSeq())
            .setPartial(dto.partial());
    ifPresent(dto.text(), b::setText);
    ifPresent(dto.model(), b::setModel);
    ifPresent(dto.createdAt(), at -> b.setCreatedAt(ProtoMapper.toProto(at)));
    return b.build();
  }

  static ContextUsage toProto(ContextUsageDto dto) {
    ContextUsage.Builder b =
        ContextUsage.newBuilder()
            .setContextLength(dto.contextLength())
            .setUsedTokens(dto.usedTokens())
            .setTotalTokens(dto.totalTokens())
            .setFits(dto.fits())
            .setTrimmedCount(dto.trimmedCount())
            .setSummaryActive(dto.summaryActive());
    ifPresent(dto.model(), b::setModel);
    ifPresent(dto.nodeId(), b::setNodeId);
    return b.build();
  }

  static ConversationMessage toProto(MessageDto dto) {
    ConversationMessage.Builder b = ConversationMessage.newBuilder().setSeq(dto.seq());
    ifPresent(dto.id(), b::setId);
    ifPresent(dto.parentId(), b::setParentId);
    ifPresent(dto.role(), b::setRole);
    ifPresent(dto.content(), b::setContent);
    ifPresent(dto.thinking(), b::setThinking);
    ifPresent(dto.status(), b::setStatus);
    dto.attachments()
        .forEach(
            a ->
                b.addAttachments(
                    MessageAttachment.newBuilder()
                        .setId(a.id())
                        .setKind(a.kind())
                        .setMimeType(a.mimeType())
                        .setSizeBytes(a.sizeBytes())
                        .setName(a.name() == null ? "" : a.name())));
    ifPresent(
        dto.error(),
        e -> b.setError(MessageError.newBuilder().setCode(e.code()).setMessage(e.message())));
    ifPresent(dto.generation(), g -> b.setGeneration(toProto(g)));
    ifPresent(dto.createdAt(), at -> b.setCreatedAt(ProtoMapper.toProto(at)));
    ifPresent(dto.editedAt(), at -> b.setEditedAt(ProtoMapper.toProto(at)));
    return b.build();
  }

  private static MessageGeneration toProto(MessageDto.GenerationDto g) {
    MessageGeneration.Builder b = MessageGeneration.newBuilder().setAuto(g.auto());
    ifPresent(g.nodeId(), b::setNodeId);
    ifPresent(g.nodeName(), b::setNodeName);
    ifPresent(g.model(), b::setModel);
    ifPresent(g.presetId(), b::setPresetId);
    ifPresent(g.presetName(), b::setPresetName);
    ifPresent(g.contextLength(), b::setContextLength);
    ifPresent(g.promptTokens(), b::setPromptTokens);
    ifPresent(g.completionTokens(), b::setCompletionTokens);
    ifPresent(g.ttftMs(), b::setTtftMs);
    ifPresent(g.totalMs(), b::setTotalMs);
    ifPresent(g.loadMs(), b::setLoadMs);
    ifPresent(g.thinkingMs(), b::setThinkingMs);
    ifPresent(g.tokensPerSecond(), b::setTokensPerSecond);
    ifPresent(g.finishReason(), b::setFinishReason);
    return b.build();
  }

  static SummarizeConversationResponse toProto(SummarizeResponseDto dto) {
    SummarizeConversationResponse.Builder b = SummarizeConversationResponse.newBuilder();
    ifPresent(dto.conversation(), c -> b.setConversation(toProto(c)));
    ifPresent(dto.summary(), s -> b.setSummary(toProto(s)));
    ifPresent(dto.context(), c -> b.setContext(toProto(c)));
    return b.build();
  }

  static DeleteResponse toProto(DeleteResponseDto dto) {
    return DeleteResponse.newBuilder().setDeleted(dto.deleted()).build();
  }

  private static ConversationSettings settings(ConversationSettingsDto dto) {
    ConversationSettings.Builder b = ConversationSettings.newBuilder();
    if (dto != null) {
      ifPresent(dto.getPresetId(), b::setPresetId);
      ifPresent(dto.getSystemPrompt(), b::setSystemPrompt);
    }
    return b.build();
  }

  // ---- requests ----------------------------------------------------------------------------

  static CreateConversationRequestDto fromProto(CreateConversationRequest request) {
    return new CreateConversationRequestDto(
        request.hasTitle() ? request.getTitle() : null,
        request.hasTarget() ? target(request.getTarget()) : null,
        request.hasSettings() ? settings(request.getSettings()) : null);
  }

  /**
   * Presence carries over: unset = left alone, set = replaced whole; an empty {@code ModelTarget}
   * clears the target, and a set {@code settings} replaces both fields.
   */
  static UpdateConversationRequestDto fromProto(UpdateConversationRequest request) {
    boolean targetPresent = request.hasTarget();
    ModelTargetDto target =
        targetPresent && !request.getTarget().equals(ModelTarget.getDefaultInstance())
            ? target(request.getTarget())
            : null;
    boolean settingsPresent = request.hasSettings();
    return new UpdateConversationRequestDto(
        request.hasTitle() ? request.getTitle() : null,
        request.hasPinned() ? request.getPinned() : null,
        targetPresent,
        target,
        settingsPresent,
        settingsPresent ? settings(request.getSettings()) : null);
  }

  static SendMessageRequestDto fromProto(SendMessageRequest request) {
    return new SendMessageRequestDto(
        request.getContent(),
        request.getAttachmentIdsList().isEmpty() ? null : List.copyOf(request.getAttachmentIdsList()),
        request.hasTarget() ? target(request.getTarget()) : null);
  }

  static RegenerateMessageRequestDto fromProto(RegenerateMessageRequest request) {
    return new RegenerateMessageRequestDto(request.hasTarget() ? target(request.getTarget()) : null);
  }

  static EditMessageRequestDto fromProto(EditMessageRequest request) {
    boolean present = request.hasAttachmentIds();
    return new EditMessageRequestDto(
        request.getContent(),
        present,
        present ? List.copyOf(request.getAttachmentIds().getValuesList()) : null,
        request.hasTarget() ? target(request.getTarget()) : null);
  }

  private static ModelTargetDto target(ModelTarget target) {
    return new ModelTargetDto(target.getModel(), target.hasNodeId() ? target.getNodeId() : null);
  }

  private static ConversationSettingsDto settings(ConversationSettings settings) {
    return new ConversationSettingsDto(
        settings.hasPresetId() ? settings.getPresetId() : null,
        settings.hasSystemPrompt() ? settings.getSystemPrompt() : null);
  }
}
