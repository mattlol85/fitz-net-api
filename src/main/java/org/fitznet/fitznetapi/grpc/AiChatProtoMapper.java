package org.fitznet.fitznetapi.grpc;

import static org.fitznet.fitznetapi.grpc.ProtoMapper.ifPresent;

import org.fitznet.fitznetapi.dto.ModelTargetDto;
import org.fitznet.fitznetapi.dto.requests.UpdatePreferencesRequestDto;
import org.fitznet.fitznetapi.dto.responses.ChatPreferencesDto;
import org.fitznet.fitznetapi.dto.responses.ModelCatalogDto;
import org.fitznet.fitznetapi.dto.responses.WarmUpResponseDto;
import org.fitznet.fitznetapi.grpc.proto.AutoModel;
import org.fitznet.fitznetapi.grpc.proto.CatalogModel;
import org.fitznet.fitznetapi.grpc.proto.CatalogNode;
import org.fitznet.fitznetapi.grpc.proto.CatalogVram;
import org.fitznet.fitznetapi.grpc.proto.ChatPreferences;
import org.fitznet.fitznetapi.grpc.proto.ModelCatalog;
import org.fitznet.fitznetapi.grpc.proto.ModelTarget;
import org.fitznet.fitznetapi.grpc.proto.UpdatePreferencesRequest;
import org.fitznet.fitznetapi.grpc.proto.WarmUpResponse;

/** Conversions between the {@code /ai} REST DTOs and the {@code ai_chat.proto} messages. */
final class AiChatProtoMapper {

  private AiChatProtoMapper() {}

  static ModelCatalog toProto(ModelCatalogDto dto) {
    ModelCatalog.Builder b = ModelCatalog.newBuilder();
    ifPresent(dto.generatedAt(), at -> b.setGeneratedAt(ProtoMapper.toProto(at)));
    dto.nodes().forEach(n -> b.addNodes(toProto(n)));
    dto.models().forEach(m -> b.addModels(toProto(m)));
    return b.build();
  }

  private static CatalogNode toProto(ModelCatalogDto.NodeEntry node) {
    CatalogNode.Builder b =
        CatalogNode.newBuilder()
            .setActiveGenerations(node.activeGenerations())
            .setCatalogStale(node.catalogStale());
    ifPresent(node.id(), b::setId);
    ifPresent(node.name(), b::setName);
    ifPresent(node.status(), b::setStatus);
    ifPresent(node.os(), b::setOs);
    ifPresent(node.lastHeartbeatAt(), at -> b.setLastHeartbeatAt(ProtoMapper.toProto(at)));
    CatalogVram.Builder vram = CatalogVram.newBuilder();
    ifPresent(node.vram().totalMb(), vram::setTotalMb);
    ifPresent(node.vram().usedMb(), vram::setUsedMb);
    ifPresent(node.vram().source(), vram::setSource);
    b.setVram(vram);
    node.models().forEach(m -> b.addModels(toProto(m)));
    return b.build();
  }

  private static CatalogModel toProto(ModelCatalogDto.ModelEntry model) {
    CatalogModel.Builder b =
        CatalogModel.newBuilder()
            .setName(model.name())
            .addAllCapabilities(model.capabilities())
            .setLoaded(model.loaded());
    ifPresent(model.family(), b::setFamily);
    ifPresent(model.parameterSize(), b::setParameterSize);
    ifPresent(model.quantization(), b::setQuantization);
    ifPresent(model.sizeBytes(), b::setSizeBytes);
    ifPresent(model.contextLength(), b::setContextLength);
    ifPresent(model.maxContextLength(), b::setMaxContextLength);
    ifPresent(model.loadedUntil(), at -> b.setLoadedUntil(ProtoMapper.toProto(at)));
    ifPresent(model.vramMb(), b::setVramMb);
    return b.build();
  }

  private static AutoModel toProto(ModelCatalogDto.AutoModelEntry model) {
    AutoModel.Builder b =
        AutoModel.newBuilder()
            .setName(model.name())
            .addAllNodeIds(model.nodeIds())
            .addAllLoadedOn(model.loadedOn())
            .addAllCapabilities(model.capabilities());
    ifPresent(model.autoNodeId(), b::setAutoNodeId);
    ifPresent(model.parameterSize(), b::setParameterSize);
    ifPresent(model.quantization(), b::setQuantization);
    ifPresent(model.contextLength(), b::setContextLength);
    return b.build();
  }

  static WarmUpResponse toProto(WarmUpResponseDto dto) {
    WarmUpResponse.Builder b = WarmUpResponse.newBuilder().setState(dto.state());
    ifPresent(dto.nodeId(), b::setNodeId);
    ifPresent(dto.nodeName(), b::setNodeName);
    ifPresent(dto.model(), b::setModel);
    ifPresent(dto.message(), b::setMessage);
    return b.build();
  }

  static ChatPreferences toProto(ChatPreferencesDto dto) {
    ChatPreferences.Builder b = ChatPreferences.newBuilder();
    ifPresent(dto.lastTarget(), target -> b.setLastTarget(toProto(target)));
    ifPresent(dto.lastPresetId(), b::setLastPresetId);
    ifPresent(dto.updatedAt(), at -> b.setUpdatedAt(ProtoMapper.toProto(at)));
    return b.build();
  }

  static ModelTarget toProto(ModelTargetDto dto) {
    ModelTarget.Builder b = ModelTarget.newBuilder();
    ifPresent(dto.getModel(), b::setModel);
    ifPresent(dto.getNodeId(), b::setNodeId);
    return b.build();
  }

  /**
   * Presence carries over: an unset field is left alone, a set field replaces the stored value, and
   * an empty {@code ModelTarget} / empty string clears it.
   */
  static UpdatePreferencesRequestDto fromProto(UpdatePreferencesRequest request) {
    boolean targetPresent = request.hasLastTarget();
    ModelTargetDto target = null;
    if (targetPresent && !request.getLastTarget().equals(ModelTarget.getDefaultInstance())) {
      target =
          new ModelTargetDto(
              request.getLastTarget().getModel(),
              request.getLastTarget().hasNodeId() ? request.getLastTarget().getNodeId() : null);
    }
    boolean presetPresent = request.hasLastPresetId();
    String presetId =
        presetPresent && !request.getLastPresetId().isEmpty() ? request.getLastPresetId() : null;
    return new UpdatePreferencesRequestDto(targetPresent, target, presetPresent, presetId);
  }
}
