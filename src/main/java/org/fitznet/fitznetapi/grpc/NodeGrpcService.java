package org.fitznet.fitznetapi.grpc;

import static org.fitznet.fitznetapi.grpc.ProtoMapper.fromProto;
import static org.fitznet.fitznetapi.grpc.ProtoMapper.optional;
import static org.fitznet.fitznetapi.grpc.ProtoMapper.reply;
import static org.fitznet.fitznetapi.grpc.ProtoMapper.toProto;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.fitznet.fitznetapi.controller.NodeController;
import org.fitznet.fitznetapi.controller.NodeEnrollmentController;
import org.fitznet.fitznetapi.dto.requests.ChatRequestDto;
import org.fitznet.fitznetapi.dto.requests.NodeEnrollmentTokenRequestDto;
import org.fitznet.fitznetapi.dto.requests.NodeHeartbeatRequestDto;
import org.fitznet.fitznetapi.dto.requests.NodeRegisterRequestDto;
import org.fitznet.fitznetapi.dto.responses.ChatResponseDto;
import org.fitznet.fitznetapi.dto.responses.NodeEnrollmentTokenResponseDto;
import org.fitznet.fitznetapi.dto.responses.NodeRegisterResponseDto;
import org.fitznet.fitznetapi.grpc.proto.ChatDone;
import org.fitznet.fitznetapi.grpc.proto.ChatError;
import org.fitznet.fitznetapi.grpc.proto.ChatEvent;
import org.fitznet.fitznetapi.grpc.proto.ChatMeta;
import org.fitznet.fitznetapi.grpc.proto.ChatRequest;
import org.fitznet.fitznetapi.grpc.proto.ChatResponse;
import org.fitznet.fitznetapi.grpc.proto.ChatToken;
import org.fitznet.fitznetapi.grpc.proto.CreateEnrollmentTokenRequest;
import org.fitznet.fitznetapi.grpc.proto.DeleteNodeRequest;
import org.fitznet.fitznetapi.grpc.proto.DeleteNodeResponse;
import org.fitznet.fitznetapi.grpc.proto.EnrollmentToken;
import org.fitznet.fitznetapi.grpc.proto.HeartbeatRequest;
import org.fitznet.fitznetapi.grpc.proto.HeartbeatResponse;
import org.fitznet.fitznetapi.grpc.proto.ListNodesRequest;
import org.fitznet.fitznetapi.grpc.proto.ListNodesResponse;
import org.fitznet.fitznetapi.grpc.proto.NodeServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.RegisterNodeRequest;
import org.fitznet.fitznetapi.grpc.proto.RegisterNodeResponse;
import org.fitznet.fitznetapi.service.ChatEventSink;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.security.core.context.SecurityContextHolder;

/** gRPC equivalent of {@link NodeController} and {@link NodeEnrollmentController}. */
@GrpcService
public class NodeGrpcService extends NodeServiceGrpc.NodeServiceImplBase {

  private final NodeController nodeController;
  private final NodeEnrollmentController nodeEnrollmentController;
  private final GrpcRequestValidator validator;

  public NodeGrpcService(
      NodeController nodeController,
      NodeEnrollmentController nodeEnrollmentController,
      GrpcRequestValidator validator) {
    this.nodeController = nodeController;
    this.nodeEnrollmentController = nodeEnrollmentController;
    this.validator = validator;
  }

  @Override
  public void registerNode(
      RegisterNodeRequest request, StreamObserver<RegisterNodeResponse> observer) {
    NodeRegisterRequestDto dto =
        validator.valid(
            new NodeRegisterRequestDto(
                request.getToken(),
                request.getName(),
                optional(request.hasOs(), request.getOs()),
                request.getModelsList().isEmpty() ? null : request.getModelsList(),
                request.hasVramGb() ? request.getVramGb() : null,
                optional(request.hasAddress(), request.getAddress())));
    NodeRegisterResponseDto registered = nodeController.registerNode(dto);
    reply(
        observer,
        RegisterNodeResponse.newBuilder()
            .setNodeId(registered.getNodeId())
            .setNodeKey(registered.getNodeKey())
            .build());
  }

  @Override
  public void heartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> observer) {
    NodeHeartbeatRequestDto dto =
        new NodeHeartbeatRequestDto(
            optional(request.hasStatus(), request.getStatus()),
            request.hasModels() ? request.getModels().getValuesList() : null,
            optional(request.hasAddress(), request.getAddress()));
    nodeController.heartbeat(
        validator.required("node_id", request.getNodeId()),
        validator.required("node_key", request.getNodeKey()),
        dto);
    reply(observer, HeartbeatResponse.getDefaultInstance());
  }

  @Override
  public void listNodes(ListNodesRequest request, StreamObserver<ListNodesResponse> observer) {
    reply(
        observer,
        ListNodesResponse.newBuilder()
            .addAllNodes(nodeController.listNodes().stream().map(ProtoMapper::toProto).toList())
            .build());
  }

  @Override
  public void chat(ChatRequest request, StreamObserver<ChatResponse> observer) {
    String nodeId = validator.required("node_id", request.getNodeId());
    ChatResponseDto chat = nodeController.chat(nodeId, validator.valid(fromProto(request)));
    reply(
        observer,
        ChatResponse.newBuilder()
            .setReply(chat.getReply())
            .setModel(chat.getModel())
            .setNodeId(chat.getNodeId())
            .build());
  }

  @Override
  public void chatStream(ChatRequest request, StreamObserver<ChatEvent> observer) {
    String nodeId = validator.required("node_id", request.getNodeId());
    ChatRequestDto dto = validator.valid(fromProto(request));
    ServerCallStreamObserver<ChatEvent> call = (ServerCallStreamObserver<ChatEvent>) observer;
    nodeController.chatStream(nodeId, dto, new GrpcChatEventSink(call));
    if (!call.isCancelled()) {
      call.onCompleted();
    }
  }

  @Override
  public void deleteNode(DeleteNodeRequest request, StreamObserver<DeleteNodeResponse> observer) {
    nodeController.deleteNode(
        validator.required("node_id", request.getNodeId()),
        validator.required("node_key", request.getNodeKey()));
    reply(observer, DeleteNodeResponse.getDefaultInstance());
  }

  @Override
  public void createEnrollmentToken(
      CreateEnrollmentTokenRequest request, StreamObserver<EnrollmentToken> observer) {
    NodeEnrollmentTokenResponseDto token =
        nodeEnrollmentController.createEnrollmentToken(
            new NodeEnrollmentTokenRequestDto(optional(request.hasLabel(), request.getLabel())),
            SecurityContextHolder.getContext().getAuthentication());
    reply(
        observer,
        EnrollmentToken.newBuilder()
            .setToken(token.getToken())
            .setExpiresAt(toProto(token.getExpiresAt()))
            .build());
  }

  /** Turns the streamer's SSE-shaped events into {@link ChatEvent} messages. */
  static final class GrpcChatEventSink implements ChatEventSink {

    private final ServerCallStreamObserver<ChatEvent> call;

    GrpcChatEventSink(ServerCallStreamObserver<ChatEvent> call) {
      this.call = call;
    }

    @Override
    public void event(String name, Map<String, ?> data) {
      ensureConnected();
      call.onNext(
          switch (name) {
            case "meta" ->
                ChatEvent.newBuilder()
                    .setMeta(
                        ChatMeta.newBuilder()
                            .setModel(String.valueOf(data.get("model")))
                            .setNodeId(String.valueOf(data.get("nodeId")))
                            .setContextLength(intValue(data, "contextLength")))
                    .build();
            case "token" ->
                ChatEvent.newBuilder()
                    .setToken(ChatToken.newBuilder().setContent(String.valueOf(data.get("content"))))
                    .build();
            case "done" ->
                ChatEvent.newBuilder()
                    .setDone(
                        ChatDone.newBuilder()
                            .setPromptTokens(intValue(data, "promptTokens"))
                            .setCompletionTokens(intValue(data, "completionTokens"))
                            .setContextLength(intValue(data, "contextLength")))
                    .build();
            case "error" ->
                ChatEvent.newBuilder()
                    .setError(ChatError.newBuilder().setMessage(String.valueOf(data.get("message"))))
                    .build();
            default -> throw new IllegalArgumentException("Unknown chat event: " + name);
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

    private static int intValue(Map<String, ?> data, String key) {
      Object value = data.get(key);
      return value instanceof Number n ? n.intValue() : 0;
    }
  }
}
