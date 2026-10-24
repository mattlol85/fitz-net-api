package org.fitznet.fitznetapi.grpc;

import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import org.fitznet.fitznetapi.dto.NodeDto;
import org.fitznet.fitznetapi.dto.requests.ChatMessageDto;
import org.fitznet.fitznetapi.dto.requests.ChatRequestDto;
import org.fitznet.fitznetapi.dto.responses.UpdateProfileResponseDto;
import org.fitznet.fitznetapi.dto.responses.UserPermissionsResponseDto;
import org.fitznet.fitznetapi.dto.responses.UserResponseDto;
import org.fitznet.fitznetapi.grpc.proto.ChatRequest;
import org.fitznet.fitznetapi.grpc.proto.ListUsersResponse;
import org.fitznet.fitznetapi.grpc.proto.Node;
import org.fitznet.fitznetapi.grpc.proto.NodeStatus;
import org.fitznet.fitznetapi.grpc.proto.Permission;
import org.fitznet.fitznetapi.grpc.proto.UpdateProfileResponse;
import org.fitznet.fitznetapi.grpc.proto.User;
import org.fitznet.fitznetapi.grpc.proto.UserPermissions;
import org.fitznet.fitznetapi.model.PermissionDefinition;

/** Conversions between the REST DTOs and the generated protobuf messages. */
final class ProtoMapper {

  private ProtoMapper() {}

  /** Completes a unary call. */
  static <T> void reply(StreamObserver<T> observer, T response) {
    observer.onNext(response);
    observer.onCompleted();
  }

  /** Proto setters reject null; DTO fields are nullable. */
  static <T> void ifPresent(T value, Consumer<T> setter) {
    if (value != null) {
      setter.accept(value);
    }
  }

  /** Proto3 {@code optional} string to the DTO's null-when-absent convention. */
  static String optional(boolean present, String value) {
    return present ? value : null;
  }

  static List<String> sorted(Collection<String> values) {
    return values == null ? List.of() : values.stream().sorted().toList();
  }

  static User toProto(UserResponseDto dto) {
    User.Builder b = User.newBuilder().addAllPermissions(sorted(dto.getPermissions()));
    ifPresent(dto.getId(), b::setId);
    ifPresent(dto.getUsername(), b::setUsername);
    ifPresent(dto.getEmail(), b::setEmail);
    ifPresent(dto.getBoardColor(), b::setBoardColor);
    return b.build();
  }

  static ListUsersResponse toProto(List<UserResponseDto> users) {
    return ListUsersResponse.newBuilder()
        .addAllUsers(users.stream().map(ProtoMapper::toProto).toList())
        .build();
  }

  static UpdateProfileResponse toProto(UpdateProfileResponseDto dto) {
    UpdateProfileResponse.Builder b =
        UpdateProfileResponse.newBuilder()
            .setSuccess(dto.isSuccess())
            .addAllPermissions(sorted(dto.getPermissions()));
    ifPresent(dto.getMessage(), b::setMessage);
    ifPresent(dto.getUsername(), b::setUsername);
    ifPresent(dto.getEmail(), b::setEmail);
    ifPresent(dto.getBoardColor(), b::setBoardColor);
    return b.build();
  }

  static Permission toProto(PermissionDefinition definition) {
    Permission.Builder b = Permission.newBuilder();
    ifPresent(definition.getName(), b::setName);
    ifPresent(definition.getDescription(), b::setDescription);
    return b.build();
  }

  static UserPermissions toProto(UserPermissionsResponseDto dto) {
    UserPermissions.Builder b =
        UserPermissions.newBuilder().addAllPermissions(sorted(dto.getPermissions()));
    ifPresent(dto.getUsername(), b::setUsername);
    return b.build();
  }

  static Node toProto(NodeDto dto) {
    Node.Builder b = Node.newBuilder().setStatus(toProto(dto.getStatus()));
    ifPresent(dto.getId(), b::setId);
    ifPresent(dto.getName(), b::setName);
    ifPresent(dto.getOs(), b::setOs);
    ifPresent(dto.getModels(), b::addAllModels);
    ifPresent(dto.getVramGb(), b::setVramGb);
    ifPresent(dto.getRegisteredAt(), at -> b.setRegisteredAt(toProto(at)));
    ifPresent(dto.getLastHeartbeatAt(), at -> b.setLastHeartbeatAt(toProto(at)));
    return b.build();
  }

  static NodeStatus toProto(org.fitznet.fitznetapi.model.NodeStatus status) {
    if (status == null) {
      return NodeStatus.NODE_STATUS_UNSPECIFIED;
    }
    return switch (status) {
      case ONLINE -> NodeStatus.NODE_STATUS_ONLINE;
      case BUSY -> NodeStatus.NODE_STATUS_BUSY;
      case OFFLINE -> NodeStatus.NODE_STATUS_OFFLINE;
    };
  }

  static Timestamp toProto(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  static ChatRequestDto fromProto(ChatRequest request) {
    List<ChatMessageDto> messages =
        request.getMessagesList().stream()
            .map(m -> new ChatMessageDto(m.getRole(), m.getContent()))
            .toList();
    ChatRequestDto dto =
        new ChatRequestDto(
            optional(request.hasPrompt(), request.getPrompt()),
            optional(request.hasModel(), request.getModel()));
    dto.setMessages(messages.isEmpty() ? null : messages);
    return dto;
  }
}
