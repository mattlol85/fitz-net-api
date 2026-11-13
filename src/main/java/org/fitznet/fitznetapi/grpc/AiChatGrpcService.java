package org.fitznet.fitznetapi.grpc;

import static org.fitznet.fitznetapi.grpc.ProtoMapper.optional;
import static org.fitznet.fitznetapi.grpc.ProtoMapper.reply;

import io.grpc.stub.StreamObserver;
import org.fitznet.fitznetapi.controller.AiCatalogController;
import org.fitznet.fitznetapi.dto.requests.WarmUpRequestDto;
import org.fitznet.fitznetapi.grpc.proto.AiChatServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.ChatPreferences;
import org.fitznet.fitznetapi.grpc.proto.GetPreferencesRequest;
import org.fitznet.fitznetapi.grpc.proto.ListModelsRequest;
import org.fitznet.fitznetapi.grpc.proto.ModelCatalog;
import org.fitznet.fitznetapi.grpc.proto.UpdatePreferencesRequest;
import org.fitznet.fitznetapi.grpc.proto.WarmUpRequest;
import org.fitznet.fitznetapi.grpc.proto.WarmUpResponse;
import org.springframework.grpc.server.service.GrpcService;

/**
 * gRPC equivalent of the {@code /ai/**} REST endpoints. Delegates to the same controller bean, so
 * {@code @PreAuthorize} (AI_USER or ADMIN), validation and error messages are shared.
 */
@GrpcService
public class AiChatGrpcService extends AiChatServiceGrpc.AiChatServiceImplBase {

  private final AiCatalogController catalogController;
  private final GrpcRequestValidator validator;

  public AiChatGrpcService(AiCatalogController catalogController, GrpcRequestValidator validator) {
    this.catalogController = catalogController;
    this.validator = validator;
  }

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
}
