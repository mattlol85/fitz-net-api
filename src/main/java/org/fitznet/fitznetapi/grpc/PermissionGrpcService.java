package org.fitznet.fitznetapi.grpc;

import static org.fitznet.fitznetapi.grpc.ProtoMapper.optional;
import static org.fitznet.fitznetapi.grpc.ProtoMapper.reply;
import static org.fitznet.fitznetapi.grpc.ProtoMapper.toProto;

import io.grpc.stub.StreamObserver;
import java.util.LinkedHashSet;
import java.util.Map;
import org.fitznet.fitznetapi.controller.PermissionController;
import org.fitznet.fitznetapi.dto.requests.CreatePermissionRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdatePermissionsRequestDto;
import org.fitznet.fitznetapi.grpc.proto.CreatePermissionRequest;
import org.fitznet.fitznetapi.grpc.proto.DeletePermissionRequest;
import org.fitznet.fitznetapi.grpc.proto.DeletePermissionResponse;
import org.fitznet.fitznetapi.grpc.proto.GetUserPermissionsRequest;
import org.fitznet.fitznetapi.grpc.proto.ListPermissionsRequest;
import org.fitznet.fitznetapi.grpc.proto.ListPermissionsResponse;
import org.fitznet.fitznetapi.grpc.proto.ListUsersRequest;
import org.fitznet.fitznetapi.grpc.proto.ListUsersResponse;
import org.fitznet.fitznetapi.grpc.proto.Permission;
import org.fitznet.fitznetapi.grpc.proto.PermissionServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.SetUserPermissionsRequest;
import org.fitznet.fitznetapi.grpc.proto.UserPermissionRequest;
import org.fitznet.fitznetapi.grpc.proto.UserPermissions;
import org.springframework.grpc.server.service.GrpcService;

/** gRPC equivalent of {@link PermissionController}; ADMIN is enforced by the controller bean. */
@GrpcService
public class PermissionGrpcService extends PermissionServiceGrpc.PermissionServiceImplBase {

  private final PermissionController permissionController;
  private final GrpcRequestValidator validator;

  public PermissionGrpcService(
      PermissionController permissionController, GrpcRequestValidator validator) {
    this.permissionController = permissionController;
    this.validator = validator;
  }

  @Override
  public void listPermissions(
      ListPermissionsRequest request, StreamObserver<ListPermissionsResponse> observer) {
    reply(
        observer,
        ListPermissionsResponse.newBuilder()
            .addAllPermissions(
                permissionController.listPermissions().stream().map(ProtoMapper::toProto).toList())
            .build());
  }

  @Override
  public void createPermission(CreatePermissionRequest request, StreamObserver<Permission> observer) {
    CreatePermissionRequestDto dto =
        validator.valid(
            new CreatePermissionRequestDto(
                request.getName(), optional(request.hasDescription(), request.getDescription())));
    reply(observer, toProto(permissionController.createPermission(dto)));
  }

  @Override
  public void deletePermission(
      DeletePermissionRequest request, StreamObserver<DeletePermissionResponse> observer) {
    permissionController.deletePermission(validator.required("permission", request.getPermission()));
    reply(observer, DeletePermissionResponse.getDefaultInstance());
  }

  @Override
  public void listUsers(ListUsersRequest request, StreamObserver<ListUsersResponse> observer) {
    reply(observer, toProto(permissionController.listUsers()));
  }

  @Override
  public void getUserPermissions(
      GetUserPermissionsRequest request, StreamObserver<UserPermissions> observer) {
    reply(
        observer,
        toProto(
            permissionController.getPermissions(
                validator.required("username", request.getUsername()))));
  }

  @Override
  public void setUserPermissions(
      SetUserPermissionsRequest request, StreamObserver<UserPermissions> observer) {
    String username = validator.required("username", request.getUsername());
    if (!request.hasPermissions()) {
      throw new RequestValidationException(Map.of("permissions", "must not be null"));
    }
    UpdatePermissionsRequestDto dto =
        new UpdatePermissionsRequestDto(
            new LinkedHashSet<>(request.getPermissions().getValuesList()));
    reply(observer, toProto(permissionController.setPermissions(username, dto)));
  }

  @Override
  public void grantUserPermission(
      UserPermissionRequest request, StreamObserver<UserPermissions> observer) {
    reply(
        observer,
        toProto(
            permissionController.addPermission(
                validator.required("username", request.getUsername()),
                validator.required("permission", request.getPermission()))));
  }

  @Override
  public void revokeUserPermission(
      UserPermissionRequest request, StreamObserver<UserPermissions> observer) {
    reply(
        observer,
        toProto(
            permissionController.removePermission(
                validator.required("username", request.getUsername()),
                validator.required("permission", request.getPermission()))));
  }
}
