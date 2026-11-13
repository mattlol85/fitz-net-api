package org.fitznet.fitznetapi.grpc;

import static org.fitznet.fitznetapi.grpc.ProtoMapper.optional;
import static org.fitznet.fitznetapi.grpc.ProtoMapper.reply;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.fitznet.fitznetapi.controller.AiCatalogController;
import org.fitznet.fitznetapi.controller.AiAttachmentController;
import org.fitznet.fitznetapi.controller.AiConversationController;
import org.fitznet.fitznetapi.controller.AiPresetController;
import org.fitznet.fitznetapi.dto.requests.EditMessageRequestDto;
import org.fitznet.fitznetapi.dto.requests.RegenerateMessageRequestDto;
import org.fitznet.fitznetapi.dto.requests.SendMessageRequestDto;
import org.fitznet.fitznetapi.dto.requests.WarmUpRequestDto;
import org.fitznet.fitznetapi.grpc.proto.AiChatServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.Attachment;
import org.fitznet.fitznetapi.grpc.proto.AttachmentContent;
import org.fitznet.fitznetapi.grpc.proto.ChatPreferences;
import org.fitznet.fitznetapi.grpc.proto.ContextUsage;
import org.fitznet.fitznetapi.grpc.proto.Conversation;
import org.fitznet.fitznetapi.grpc.proto.ConversationDetail;
import org.fitznet.fitznetapi.grpc.proto.CreateConversationRequest;
import org.fitznet.fitznetapi.grpc.proto.CreatePresetRequest;
import org.fitznet.fitznetapi.grpc.proto.DeleteConversationRequest;
import org.fitznet.fitznetapi.grpc.proto.DeletePresetRequest;
import org.fitznet.fitznetapi.grpc.proto.DeleteResponse;
import org.fitznet.fitznetapi.grpc.proto.EditMessageRequest;
import org.fitznet.fitznetapi.grpc.proto.GenerateTitleRequest;
import org.fitznet.fitznetapi.grpc.proto.GetContextUsageRequest;
import org.fitznet.fitznetapi.grpc.proto.GetAttachmentRequest;
import org.fitznet.fitznetapi.grpc.proto.GetConversationRequest;
import org.fitznet.fitznetapi.grpc.proto.GetPreferencesRequest;
import org.fitznet.fitznetapi.grpc.proto.ListConversationsRequest;
import org.fitznet.fitznetapi.grpc.proto.ListConversationsResponse;
import org.fitznet.fitznetapi.grpc.proto.ListModelsRequest;
import org.fitznet.fitznetapi.grpc.proto.ListPresetsRequest;
import org.fitznet.fitznetapi.grpc.proto.ListPresetsResponse;
import org.fitznet.fitznetapi.grpc.proto.ModelCatalog;
import org.fitznet.fitznetapi.grpc.proto.Preset;
import org.fitznet.fitznetapi.grpc.proto.RegenerateMessageRequest;
import org.fitznet.fitznetapi.grpc.proto.SendMessageRequest;
import org.fitznet.fitznetapi.grpc.proto.SummarizeConversationRequest;
import org.fitznet.fitznetapi.grpc.proto.SummarizeConversationResponse;
import org.fitznet.fitznetapi.grpc.proto.TurnAlternative;
import org.fitznet.fitznetapi.grpc.proto.TurnContext;
import org.fitznet.fitznetapi.grpc.proto.TurnDelta;
import org.fitznet.fitznetapi.grpc.proto.TurnDone;
import org.fitznet.fitznetapi.grpc.proto.TurnError;
import org.fitznet.fitznetapi.grpc.proto.TurnEvent;
import org.fitznet.fitznetapi.grpc.proto.TurnMeta;
import org.fitznet.fitznetapi.grpc.proto.TurnStats;
import org.fitznet.fitznetapi.grpc.proto.TurnStatus;
import org.fitznet.fitznetapi.grpc.proto.UpdateConversationRequest;
import org.fitznet.fitznetapi.grpc.proto.UpdatePresetRequest;
import org.fitznet.fitznetapi.grpc.proto.UpdatePreferencesRequest;
import org.fitznet.fitznetapi.grpc.proto.UploadAttachmentRequest;
import org.fitznet.fitznetapi.grpc.proto.WarmUpRequest;
import org.fitznet.fitznetapi.grpc.proto.WarmUpResponse;
import org.fitznet.fitznetapi.service.ChatEventSink;
import org.springframework.grpc.server.service.GrpcService;

/**
 * gRPC equivalent of the {@code /ai/**} REST endpoints. Delegates to the same controller beans, so
 * {@code @PreAuthorize} (AI_USER or ADMIN), validation, owner scoping and error messages are shared.
 * The three streaming RPCs hand the controller a {@link GrpcTurnEventSink} that throws
 * {@link UncheckedIOException} once the call is cancelled, which makes the turn engine drop the
 * upstream Ollama connection.
 */
@GrpcService
public class AiChatGrpcService extends AiChatServiceGrpc.AiChatServiceImplBase {

  private final AiCatalogController catalogController;
  private final AiConversationController conversationController;
  private final AiPresetController presetController;
  private final AiAttachmentController attachmentController;
  private final GrpcRequestValidator validator;

  public AiChatGrpcService(
      AiCatalogController catalogController,
      AiConversationController conversationController,
      AiPresetController presetController,
      AiAttachmentController attachmentController,
      GrpcRequestValidator validator) {
    this.catalogController = catalogController;
    this.conversationController = conversationController;
    this.presetController = presetController;
    this.attachmentController = attachmentController;
    this.validator = validator;
  }

  // ---- node / model layer ------------------------------------------------------------------

  @Override
  public void listModels(ListModelsRequest request, StreamObserver<ModelCatalog> observer) {
    reply(observer, AiChatProtoMapper.toProto(catalogController.listModels(request.getRefresh())));
  }

  @Override
  public void warmUp(WarmUpRequest request, StreamObserver<WarmUpResponse> observer) {
    WarmUpRequestDto dto =
        validator.valid(
            new WarmUpRequestDto(
                request.getModel(), optional(request.hasNodeId(), request.getNodeId())));
    reply(observer, AiChatProtoMapper.toProto(catalogController.warmUp(dto)));
  }

  @Override
  public void getPreferences(
      GetPreferencesRequest request, StreamObserver<ChatPreferences> observer) {
    reply(observer, AiChatProtoMapper.toProto(catalogController.getPreferences()));
  }

  @Override
  public void updatePreferences(
      UpdatePreferencesRequest request, StreamObserver<ChatPreferences> observer) {
    reply(
        observer,
        AiChatProtoMapper.toProto(
            catalogController.updatePreferences(
                validator.valid(AiChatProtoMapper.fromProto(request)))));
  }

  // ---- conversations -----------------------------------------------------------------------

  @Override
  public void listConversations(
      ListConversationsRequest request, StreamObserver<ListConversationsResponse> observer) {
    reply(
        observer,
        AiConversationProtoMapper.toProto(
            conversationController.listConversations(
                optional(request.hasQ(), request.getQ()),
                request.hasLimit() ? request.getLimit() : null,
                request.hasOffset() ? request.getOffset() : null)));
  }

  @Override
  public void createConversation(
      CreateConversationRequest request, StreamObserver<Conversation> observer) {
    reply(
        observer,
        AiConversationProtoMapper.toProto(
            conversationController.createConversation(
                validator.valid(AiConversationProtoMapper.fromProto(request)))));
  }

  @Override
  public void getConversation(
      GetConversationRequest request, StreamObserver<ConversationDetail> observer) {
    reply(
        observer,
        AiConversationProtoMapper.toProto(
            conversationController.getConversation(
                validator.required("conversation_id", request.getConversationId()))));
  }

  @Override
  public void updateConversation(
      UpdateConversationRequest request, StreamObserver<Conversation> observer) {
    String id = validator.required("conversation_id", request.getConversationId());
    reply(
        observer,
        AiConversationProtoMapper.toProto(
            conversationController.updateConversation(
                id, validator.valid(AiConversationProtoMapper.fromProto(request)))));
  }

  @Override
  public void deleteConversation(
      DeleteConversationRequest request, StreamObserver<DeleteResponse> observer) {
    reply(
        observer,
        AiConversationProtoMapper.toProto(
            conversationController.deleteConversation(
                validator.required("conversation_id", request.getConversationId()))));
  }

  @Override
  public void generateTitle(GenerateTitleRequest request, StreamObserver<Conversation> observer) {
    reply(
        observer,
        AiConversationProtoMapper.toProto(
            conversationController.generateTitle(
                validator.required("conversation_id", request.getConversationId()))));
  }

  @Override
  public void getContextUsage(
      GetContextUsageRequest request, StreamObserver<ContextUsage> observer) {
    reply(
        observer,
        AiConversationProtoMapper.toProto(
            conversationController.getContextUsage(
                validator.required("conversation_id", request.getConversationId()),
                optional(request.hasModel(), request.getModel()),
                optional(request.hasNodeId(), request.getNodeId()))));
  }

  @Override
  public void summarizeConversation(
      SummarizeConversationRequest request,
      StreamObserver<SummarizeConversationResponse> observer) {
    reply(
        observer,
        AiConversationProtoMapper.toProto(
            conversationController.summarizeConversation(
                validator.required("conversation_id", request.getConversationId()))));
  }

  // ---- attachments -------------------------------------------------------------------------

  @Override
  public void uploadAttachment(
      UploadAttachmentRequest request, StreamObserver<Attachment> observer) {
    reply(
        observer,
        AiPresetProtoMapper.toProto(
            attachmentController.uploadAttachment(
                request.getName(), request.getMimeType(), request.getData().toByteArray())));
  }

  @Override
  public void getAttachment(
      GetAttachmentRequest request, StreamObserver<AttachmentContent> observer) {
    reply(
        observer,
        AiPresetProtoMapper.toProto(
            attachmentController.readAttachment(
                validator.required("attachment_id", request.getAttachmentId()))));
  }

  // ---- presets -----------------------------------------------------------------------------

  @Override
  public void listPresets(ListPresetsRequest request, StreamObserver<ListPresetsResponse> observer) {
    reply(observer, AiPresetProtoMapper.toProto(presetController.listPresets()));
  }

  @Override
  public void createPreset(CreatePresetRequest request, StreamObserver<Preset> observer) {
    reply(
        observer,
        AiPresetProtoMapper.toProto(
            presetController.createPreset(validator.valid(AiPresetProtoMapper.fromProto(request)))));
  }

  @Override
  public void updatePreset(UpdatePresetRequest request, StreamObserver<Preset> observer) {
    String id = validator.required("preset_id", request.getPresetId());
    reply(
        observer,
        AiPresetProtoMapper.toProto(
            presetController.updatePreset(
                id, validator.valid(AiPresetProtoMapper.fromProto(request)))));
  }

  @Override
  public void deletePreset(DeletePresetRequest request, StreamObserver<DeleteResponse> observer) {
    reply(
        observer,
        AiConversationProtoMapper.toProto(
            presetController.deletePreset(
                validator.required("preset_id", request.getPresetId()))));
  }

  // ---- turns (server streams) --------------------------------------------------------------

  @Override
  public void sendMessage(SendMessageRequest request, StreamObserver<TurnEvent> observer) {
    long receivedAtNanos = System.nanoTime(); // first statement: the stats clock starts here
    String id = validator.required("conversation_id", request.getConversationId());
    SendMessageRequestDto dto = validator.valid(AiConversationProtoMapper.fromProto(request));
    stream(observer, sink -> conversationController.sendMessage(id, dto, sink, receivedAtNanos));
  }

  @Override
  public void regenerateMessage(
      RegenerateMessageRequest request, StreamObserver<TurnEvent> observer) {
    long receivedAtNanos = System.nanoTime();
    String id = validator.required("conversation_id", request.getConversationId());
    String messageId = validator.required("message_id", request.getMessageId());
    RegenerateMessageRequestDto dto =
        validator.valid(AiConversationProtoMapper.fromProto(request));
    stream(observer, sink ->
            conversationController.regenerateMessage(id, messageId, dto, sink, receivedAtNanos));
  }

  @Override
  public void editMessage(EditMessageRequest request, StreamObserver<TurnEvent> observer) {
    long receivedAtNanos = System.nanoTime();
    String id = validator.required("conversation_id", request.getConversationId());
    String messageId = validator.required("message_id", request.getMessageId());
    EditMessageRequestDto dto = validator.valid(AiConversationProtoMapper.fromProto(request));
    stream(observer, sink -> conversationController.editMessage(id, messageId, dto, sink, receivedAtNanos));
  }

  private void stream(StreamObserver<TurnEvent> observer, Consumer<ChatEventSink> turn) {
    ServerCallStreamObserver<TurnEvent> call = (ServerCallStreamObserver<TurnEvent>) observer;
    turn.accept(new GrpcTurnEventSink(call));
    if (!call.isCancelled()) {
      call.onCompleted();
    }
  }

  /** Turns the turn engine's SSE-shaped events into {@link TurnEvent} messages. */
  static final class GrpcTurnEventSink implements ChatEventSink {

    private final ServerCallStreamObserver<TurnEvent> call;

    GrpcTurnEventSink(ServerCallStreamObserver<TurnEvent> call) {
      this.call = call;
    }

    @Override
    public void event(String name, Map<String, ?> data) {
      ensureConnected();
      call.onNext(
          switch (name) {
            case "meta" -> TurnEvent.newBuilder().setMeta(meta(data)).build();
            case "status" -> TurnEvent.newBuilder().setStatus(status(data)).build();
            case "thinking" ->
                TurnEvent.newBuilder()
                    .setThinking(TurnDelta.newBuilder().setContent(text(data, "content")))
                    .build();
            case "token" ->
                TurnEvent.newBuilder()
                    .setToken(TurnDelta.newBuilder().setContent(text(data, "content")))
                    .build();
            case "done" -> TurnEvent.newBuilder().setDone(done(data)).build();
            case "error" -> TurnEvent.newBuilder().setError(error(data)).build();
            default -> throw new IllegalArgumentException("Unknown turn event: " + name);
          });
    }

    /** HTTP/2 keeps the stream alive on its own; just notice a cancelled call promptly. */
    @Override
    public void keepAlive() {
      ensureConnected();
    }

    private void ensureConnected() {
      if (call.isCancelled()) {
        throw new UncheckedIOException(new IOException("gRPC client cancelled the call"));
      }
    }

    private static TurnMeta meta(Map<String, ?> data) {
      Map<?, ?> context = data.get("context") instanceof Map<?, ?> m ? m : Map.of();
      TurnMeta.Builder b =
          TurnMeta.newBuilder()
              .setConversationId(text(data, "conversationId"))
              .setUserMessageId(text(data, "userMessageId"))
              .setAssistantMessageId(text(data, "assistantMessageId"))
              .setNodeId(text(data, "nodeId"))
              .setNodeName(text(data, "nodeName"))
              .setModel(text(data, "model"))
              .setAuto(bool(data, "auto"))
              .setContextLength(integer(data, "contextLength"))
              .setContext(
                  TurnContext.newBuilder()
                      .setPromptTokensEstimate(integer(context, "promptTokensEstimate"))
                      .setTrimmedCount(integer(context, "trimmedCount"))
                      .setSummaryActive(bool(context, "summaryActive"))
                      .setImagesOmitted(integer(context, "imagesOmitted")));
      if (data.get("presetId") != null) {
        b.setPresetId(text(data, "presetId"));
      }
      return b.build();
    }

    private static TurnStatus status(Map<String, ?> data) {
      TurnStatus.Builder b = TurnStatus.newBuilder().setPhase(text(data, "phase"));
      if (data.get("nodeId") != null) {
        b.setNodeId(text(data, "nodeId"));
      }
      if (data.get("model") != null) {
        b.setModel(text(data, "model"));
      }
      if (data.get("message") != null) {
        b.setMessage(text(data, "message"));
      }
      if (data.get("fromNodeId") != null) {
        b.setFromNodeId(text(data, "fromNodeId"));
      }
      if (data.get("toNodeId") != null) {
        b.setToNodeId(text(data, "toNodeId"));
      }
      return b.build();
    }

    private static TurnDone done(Map<String, ?> data) {
      Map<?, ?> stats = data.get("stats") instanceof Map<?, ?> m ? m : Map.of();
      TurnStats.Builder s =
          TurnStats.newBuilder()
              .setTotalMs(longValue(stats, "totalMs"))
              .setLoadMs(longValue(stats, "loadMs"));
      if (stats.get("ttftMs") instanceof Number n) {
        s.setTtftMs(n.longValue());
      }
      if (stats.get("thinkingMs") instanceof Number n) {
        s.setThinkingMs(n.longValue());
      }
      if (stats.get("tokensPerSecond") instanceof Number n) {
        s.setTokensPerSecond(n.doubleValue());
      }
      return TurnDone.newBuilder()
          .setMessageId(text(data, "messageId"))
          .setUserMessageId(text(data, "userMessageId"))
          .setPromptTokens(integer(data, "promptTokens"))
          .setCompletionTokens(integer(data, "completionTokens"))
          .setContextLength(integer(data, "contextLength"))
          .setFinishReason(text(data, "finishReason"))
          .setContextOverflow(bool(data, "contextOverflow"))
          .setStats(s)
          .build();
    }

    private static TurnError error(Map<String, ?> data) {
      TurnError.Builder b =
          TurnError.newBuilder()
              .setMessageId(text(data, "messageId"))
              .setUserMessageId(text(data, "userMessageId"))
              .setCode(text(data, "code"))
              .setMessage(text(data, "message"))
              .setRetryable(bool(data, "retryable"));
      if (data.get("alternatives") instanceof List<?> alternatives) {
        for (Object item : alternatives) {
          if (item instanceof Map<?, ?> alt) {
            b.addAlternatives(
                TurnAlternative.newBuilder()
                    .setNodeId(text(alt, "nodeId"))
                    .setNodeName(text(alt, "nodeName"))
                    .setModel(text(alt, "model"))
                    .setLoaded(bool(alt, "loaded"))
                    .setStatus(text(alt, "status")));
          }
        }
      }
      return b.build();
    }

    private static String text(Map<?, ?> data, String key) {
      Object value = data.get(key);
      return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Map<?, ?> data, String key) {
      return data.get(key) instanceof Number n ? n.intValue() : 0;
    }

    private static long longValue(Map<?, ?> data, String key) {
      return data.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean bool(Map<?, ?> data, String key) {
      return data.get(key) instanceof Boolean b && b;
    }
  }
}
