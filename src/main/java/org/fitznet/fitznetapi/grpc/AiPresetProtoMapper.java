package org.fitznet.fitznetapi.grpc;

import static org.fitznet.fitznetapi.grpc.ProtoMapper.ifPresent;

import com.google.protobuf.ByteString;
import org.fitznet.fitznetapi.dto.requests.PresetRequestDto;
import org.fitznet.fitznetapi.dto.responses.AttachmentContentDto;
import org.fitznet.fitznetapi.dto.responses.AttachmentDto;
import org.fitznet.fitznetapi.dto.responses.PresetDto;
import org.fitznet.fitznetapi.dto.responses.PresetListDto;
import org.fitznet.fitznetapi.grpc.proto.Attachment;
import org.fitznet.fitznetapi.grpc.proto.AttachmentContent;
import org.fitznet.fitznetapi.grpc.proto.CreatePresetRequest;
import org.fitznet.fitznetapi.grpc.proto.ListPresetsResponse;
import org.fitznet.fitznetapi.grpc.proto.Preset;
import org.fitznet.fitznetapi.grpc.proto.UpdatePresetRequest;

/** Conversions between the preset and attachment REST DTOs and their {@code ai_chat.proto} messages. */
final class AiPresetProtoMapper {

  private AiPresetProtoMapper() {}

  static Preset toProto(PresetDto dto) {
    Preset.Builder b = Preset.newBuilder().setEditable(dto.editable());
    ifPresent(dto.id(), b::setId);
    ifPresent(dto.name(), b::setName);
    ifPresent(dto.description(), b::setDescription);
    ifPresent(dto.model(), b::setModel);
    ifPresent(dto.nodeId(), b::setNodeId);
    ifPresent(dto.systemPrompt(), b::setSystemPrompt);
    ifPresent(dto.temperature(), b::setTemperature);
    ifPresent(dto.numCtx(), b::setNumCtx);
    ifPresent(dto.visibility(), b::setVisibility);
    ifPresent(dto.requiredPermission(), b::setRequiredPermission);
    ifPresent(dto.ownerUsername(), b::setOwnerUsername);
    ifPresent(dto.createdAt(), at -> b.setCreatedAt(ProtoMapper.toProto(at)));
    ifPresent(dto.updatedAt(), at -> b.setUpdatedAt(ProtoMapper.toProto(at)));
    return b.build();
  }

  static ListPresetsResponse toProto(PresetListDto dto) {
    ListPresetsResponse.Builder b = ListPresetsResponse.newBuilder();
    dto.items().forEach(item -> b.addItems(toProto(item)));
    return b.build();
  }

  static Attachment toProto(AttachmentDto dto) {
    Attachment.Builder b = Attachment.newBuilder().setSizeBytes(dto.sizeBytes());
    ifPresent(dto.id(), b::setId);
    ifPresent(dto.kind(), b::setKind);
    ifPresent(dto.mimeType(), b::setMimeType);
    ifPresent(dto.name(), b::setName);
    return b.build();
  }

  static AttachmentContent toProto(AttachmentContentDto dto) {
    return AttachmentContent.newBuilder()
        .setMimeType(dto.mimeType())
        .setData(ByteString.copyFrom(dto.data()))
        .build();
  }

  static PresetRequestDto fromProto(CreatePresetRequest request) {
    return new PresetRequestDto(
        request.getName(),
        request.hasDescription() ? request.getDescription() : null,
        request.hasModel() ? request.getModel() : null,
        request.hasNodeId() ? request.getNodeId() : null,
        request.hasSystemPrompt() ? request.getSystemPrompt() : null,
        request.hasTemperature() ? request.getTemperature() : null,
        request.hasNumCtx() ? request.getNumCtx() : null,
        request.hasVisibility() ? request.getVisibility() : null,
        request.hasRequiredPermission() ? request.getRequiredPermission() : null);
  }

  static PresetRequestDto fromProto(UpdatePresetRequest request) {
    return new PresetRequestDto(
        request.getName(),
        request.hasDescription() ? request.getDescription() : null,
        request.hasModel() ? request.getModel() : null,
        request.hasNodeId() ? request.getNodeId() : null,
        request.hasSystemPrompt() ? request.getSystemPrompt() : null,
        request.hasTemperature() ? request.getTemperature() : null,
        request.hasNumCtx() ? request.getNumCtx() : null,
        request.hasVisibility() ? request.getVisibility() : null,
        request.hasRequiredPermission() ? request.getRequiredPermission() : null);
  }
}
