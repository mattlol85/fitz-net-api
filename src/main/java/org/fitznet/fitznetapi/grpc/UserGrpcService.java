package org.fitznet.fitznetapi.grpc;

import static org.fitznet.fitznetapi.grpc.ProtoMapper.ifPresent;
import static org.fitznet.fitznetapi.grpc.ProtoMapper.optional;
import static org.fitznet.fitznetapi.grpc.ProtoMapper.reply;
import static org.fitznet.fitznetapi.grpc.ProtoMapper.sorted;
import static org.fitznet.fitznetapi.grpc.ProtoMapper.toProto;

import io.grpc.stub.StreamObserver;
import org.fitznet.fitznetapi.controller.UserController;
import org.fitznet.fitznetapi.dto.UserDTO;
import org.fitznet.fitznetapi.dto.requests.LoginRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateProfileRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateUserRequestDto;
import org.fitznet.fitznetapi.dto.responses.DeleteUserResponseDto;
import org.fitznet.fitznetapi.dto.responses.LoginResponseDto;
import org.fitznet.fitznetapi.dto.responses.UserResponseDto;
import org.fitznet.fitznetapi.grpc.proto.CreateUserRequest;
import org.fitznet.fitznetapi.grpc.proto.DeleteMeRequest;
import org.fitznet.fitznetapi.grpc.proto.DeleteUserResponse;
import org.fitznet.fitznetapi.grpc.proto.GetMeRequest;
import org.fitznet.fitznetapi.grpc.proto.ListUsersRequest;
import org.fitznet.fitznetapi.grpc.proto.ListUsersResponse;
import org.fitznet.fitznetapi.grpc.proto.LoginRequest;
import org.fitznet.fitznetapi.grpc.proto.LoginResponse;
import org.fitznet.fitznetapi.grpc.proto.ReadUserRequest;
import org.fitznet.fitznetapi.grpc.proto.ReadUserResponse;
import org.fitznet.fitznetapi.grpc.proto.UpdateProfileRequest;
import org.fitznet.fitznetapi.grpc.proto.UpdateProfileResponse;
import org.fitznet.fitznetapi.grpc.proto.UpdateUserRequest;
import org.fitznet.fitznetapi.grpc.proto.User;
import org.fitznet.fitznetapi.grpc.proto.UserServiceGrpc;
import org.springframework.grpc.server.service.GrpcService;

/**
 * gRPC equivalent of {@link UserController}. Delegates to the controller bean so its validation,
 * {@code @PreAuthorize} rules and error responses are shared with REST.
 */
@GrpcService
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {

  private final UserController userController;
  private final GrpcRequestValidator validator;

  public UserGrpcService(UserController userController, GrpcRequestValidator validator) {
    this.userController = userController;
    this.validator = validator;
  }

  @Override
  public void createUser(CreateUserRequest request, StreamObserver<User> observer) {
    UserDTO dto =
        validator.valid(
            new UserDTO(
                request.getUsername(),
                optional(request.hasEmail(), request.getEmail()),
                request.getPassword()));
    reply(observer, toProto(userController.createUser(dto)));
  }

  @Override
  public void readUser(ReadUserRequest request, StreamObserver<ReadUserResponse> observer) {
    UserResponseDto user =
        userController.readUser(validator.required("username", request.getUsername()));
    ReadUserResponse.Builder response = ReadUserResponse.newBuilder();
    ifPresent(user, u -> response.setUser(toProto(u)));
    reply(observer, response.build());
  }

  @Override
  public void getMe(GetMeRequest request, StreamObserver<User> observer) {
    reply(observer, toProto(userController.me()));
  }

  @Override
  public void listUsers(ListUsersRequest request, StreamObserver<ListUsersResponse> observer) {
    reply(observer, toProto(userController.readAllUsers()));
  }

  @Override
  public void deleteMe(DeleteMeRequest request, StreamObserver<DeleteUserResponse> observer) {
    DeleteUserResponseDto deleted = userController.deleteUser();
    reply(
        observer,
        DeleteUserResponse.newBuilder()
            .setSuccess(deleted.isSuccess())
            .setMessage(deleted.getMessage())
            .setUsername(deleted.getUsername())
            .build());
  }

  @Override
  public void updateUser(UpdateUserRequest request, StreamObserver<UpdateProfileResponse> observer) {
    UpdateUserRequestDto dto =
        new UpdateUserRequestDto(
            null,
            optional(request.hasUpdatedUsername(), request.getUpdatedUsername()),
            optional(request.hasUpdatedEmail(), request.getUpdatedEmail()),
            optional(request.hasUpdatedPassword(), request.getUpdatedPassword()));
    reply(observer, toProto(userController.updateUser(validator.valid(dto))));
  }

  @Override
  public void updateProfile(
      UpdateProfileRequest request, StreamObserver<UpdateProfileResponse> observer) {
    UpdateProfileRequestDto dto =
        new UpdateProfileRequestDto(
            request.getUsername(),
            request.getEmail(),
            optional(request.hasPassword(), request.getPassword()));
    reply(observer, toProto(userController.updateProfile(validator.valid(dto))));
  }

  @Override
  public void login(LoginRequest request, StreamObserver<LoginResponse> observer) {
    LoginResponseDto login =
        userController.login(
            validator.valid(new LoginRequestDto(request.getUsername(), request.getPassword())));
    LoginResponse.Builder response =
        LoginResponse.newBuilder()
            .setSuccess(login.isSuccess())
            .setMessage(login.getMessage())
            .setUsername(login.getUsername())
            .setToken(login.getToken())
            .addAllPermissions(sorted(login.getPermissions()));
    ifPresent(login.getEmail(), response::setEmail);
    ifPresent(login.getBoardColor(), response::setBoardColor);
    reply(observer, response.build());
  }
}
