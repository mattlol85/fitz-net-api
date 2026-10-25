package org.fitznet.fitznetapi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.grpc.proto.ChatEvent;
import org.fitznet.fitznetapi.grpc.proto.ChatMessage;
import org.fitznet.fitznetapi.grpc.proto.ChatRequest;
import org.fitznet.fitznetapi.grpc.proto.CreateEnrollmentTokenRequest;
import org.fitznet.fitznetapi.grpc.proto.CreatePermissionRequest;
import org.fitznet.fitznetapi.grpc.proto.CreateUserRequest;
import org.fitznet.fitznetapi.grpc.proto.DeleteNodeRequest;
import org.fitznet.fitznetapi.grpc.proto.GetInfoRequest;
import org.fitznet.fitznetapi.grpc.proto.GetMeRequest;
import org.fitznet.fitznetapi.grpc.proto.HeartbeatRequest;
import org.fitznet.fitznetapi.grpc.proto.InfoServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.ListNodesRequest;
import org.fitznet.fitznetapi.grpc.proto.ListPermissionsRequest;
import org.fitznet.fitznetapi.grpc.proto.ListUsersRequest;
import org.fitznet.fitznetapi.grpc.proto.LoginRequest;
import org.fitznet.fitznetapi.grpc.proto.ModelList;
import org.fitznet.fitznetapi.grpc.proto.NodeServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.NodeStatus;
import org.fitznet.fitznetapi.grpc.proto.PermissionList;
import org.fitznet.fitznetapi.grpc.proto.PermissionServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.ReadUserRequest;
import org.fitznet.fitznetapi.grpc.proto.RegisterNodeRequest;
import org.fitznet.fitznetapi.grpc.proto.SetUserPermissionsRequest;
import org.fitznet.fitznetapi.grpc.proto.UpdateProfileRequest;
import org.fitznet.fitznetapi.grpc.proto.UserPermissionRequest;
import org.fitznet.fitznetapi.grpc.proto.UserServiceGrpc;
import org.fitznet.fitznetapi.model.AiNode;
import org.fitznet.fitznetapi.model.PermissionDefinition;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.repository.PermissionRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The gRPC server end to end over a real socket: auth, error mapping and each service. */
@SpringBootTest
@Import(EmbeddedMongoTestConfiguration.class)
class GrpcIntegrationTest {

  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> ADMIN_KEY =
      Metadata.Key.of("x-admin-key", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> HTTP_STATUS =
      Metadata.Key.of("x-http-status", Metadata.ASCII_STRING_MARSHALLER);

  @LocalGrpcServerPort private int port;
  @Autowired private UserRepository userRepository;
  @Autowired private PermissionRepository permissionRepository;
  @Autowired private AiNodeRepository aiNodeRepository;
  @Autowired private JwtUtil jwtUtil;

  private ManagedChannel channel;
  private HttpServer fakeOllama;

  @BeforeEach
  void setUp() throws Exception {
    channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    userRepository.deleteAll();
    aiNodeRepository.deleteAll();
    permissionRepository.deleteAll();
    permissionRepository.save(PermissionDefinition.builder().name("ADMIN").build());
    permissionRepository.save(PermissionDefinition.builder().name("AI_USER").build());
    permissionRepository.save(PermissionDefinition.builder().name("RADARR").build());

    fakeOllama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fakeOllama.createContext(
        "/api/chat",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body =
              ("{\"message\":{\"content\":\"pong\"},\"done\":false}\n"
                      + "{\"message\":{\"content\":\"\"},\"done\":true,"
                      + "\"prompt_eval_count\":3,\"eval_count\":1}\n")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    fakeOllama.start();
  }

  @AfterEach
  void tearDown() {
    channel.shutdownNow();
    fakeOllama.stop(0);
    aiNodeRepository.deleteAll();
  }

  private <S extends AbstractStub<S>> S withToken(S stub, String token) {
    Metadata headers = new Metadata();
    headers.put(AUTHORIZATION, "Bearer " + token);
    return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  private <S extends AbstractStub<S>> S withAdminKey(S stub) {
    Metadata headers = new Metadata();
    headers.put(ADMIN_KEY, "test-admin-api-key");
    return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  private static void assertStatus(Status.Code code, String httpStatus, Executable call) {
    StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, call);
    assertEquals(code, ex.getStatus().getCode(), ex.getStatus().toString());
    assertEquals(httpStatus, ex.getTrailers().get(HTTP_STATUS));
  }

  private String saveUser(String username, String... permissions) {
    userRepository.save(
        User.builder()
            .username(username)
            .email(username + "@example.com")
            .password("x")
            .permissions(new HashSet<>(Set.of(permissions)))
            .build());
    return jwtUtil.generateToken(username);
  }

  @Test
  void correlationIdIsEchoedInResponseHeaders() {
    Metadata sent = new Metadata();
    sent.put(Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER), "trace-abc-123");
    AtomicReference<Metadata> received = new AtomicReference<>();
    InfoServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            MetadataUtils.newAttachHeadersInterceptor(sent),
            MetadataUtils.newCaptureMetadataInterceptor(received, new AtomicReference<>()))
        .getInfo(GetInfoRequest.getDefaultInstance());
    assertEquals(
        "trace-abc-123",
        received.get().get(Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER)));
  }

  @Test
  void correlationIdIsGeneratedWhenMissing() {
    AtomicReference<Metadata> received = new AtomicReference<>();
    InfoServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            MetadataUtils.newCaptureMetadataInterceptor(received, new AtomicReference<>()))
        .getInfo(GetInfoRequest.getDefaultInstance());
    assertTrue(
        received.get().get(Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER))
            != null);
  }

  @Test
  void info_isPublic() {
    var info = InfoServiceGrpc.newBlockingStub(channel).getInfo(GetInfoRequest.getDefaultInstance());
    assertEquals("alive", info.getStatus());
  }

  @Test
  void user_createLoginAndMe() {
    var users = UserServiceGrpc.newBlockingStub(channel);
    var created =
        users.createUser(
            CreateUserRequest.newBuilder()
                .setUsername("Alice")
                .setEmail("alice@example.com")
                .setPassword("password123")
                .build());
    assertEquals("alice", created.getUsername());
    assertFalse(created.getId().isEmpty());

    var login =
        users.login(
            LoginRequest.newBuilder().setUsername("alice").setPassword("password123").build());
    assertTrue(login.getSuccess());

    var me = withToken(users, login.getToken()).getMe(GetMeRequest.getDefaultInstance());
    assertEquals("alice", me.getUsername());
    assertEquals("alice@example.com", me.getEmail());

    var updated =
        withToken(users, login.getToken())
            .updateProfile(
                UpdateProfileRequest.newBuilder()
                    .setUsername("alice")
                    .setEmail("alice@new.example.com")
                    .build());
    assertEquals("alice@new.example.com", updated.getEmail());
  }

  @Test
  void user_errorsMapLikeRest() {
    var users = UserServiceGrpc.newBlockingStub(channel);
    assertStatus(
        Status.Code.UNAUTHENTICATED, "401", () -> users.getMe(GetMeRequest.getDefaultInstance()));
    assertStatus(
        Status.Code.UNAUTHENTICATED,
        "401",
        () -> withToken(users, "not-a-jwt").getMe(GetMeRequest.getDefaultInstance()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> users.createUser(CreateUserRequest.newBuilder().setUsername("bob").build()));
    assertStatus(
        Status.Code.UNAUTHENTICATED,
        "401",
        () -> users.login(LoginRequest.newBuilder().setUsername("x").setPassword("y").build()));

    saveUser("bob", "USER");
    assertStatus(
        Status.Code.ALREADY_EXISTS,
        "409",
        () ->
            users.createUser(
                CreateUserRequest.newBuilder()
                    .setUsername("bob")
                    .setPassword("password123")
                    .build()));
  }

  @Test
  void user_adminRpcsRequireAdmin() {
    String plain = saveUser("plain", "USER");
    var users = UserServiceGrpc.newBlockingStub(channel);
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        "403",
        () -> withToken(users, plain).listUsers(ListUsersRequest.getDefaultInstance()));

    assertEquals(
        1, withAdminKey(users).listUsers(ListUsersRequest.getDefaultInstance()).getUsersCount());
    var read =
        withAdminKey(users).readUser(ReadUserRequest.newBuilder().setUsername("plain").build());
    assertEquals("plain", read.getUser().getUsername());
    var missing =
        withAdminKey(users).readUser(ReadUserRequest.newBuilder().setUsername("ghost").build());
    assertFalse(missing.hasUser());
  }

  @Test
  void permissions_manageWithAdminKey() {
    saveUser("carol", "USER");
    var perms = withAdminKey(PermissionServiceGrpc.newBlockingStub(channel));

    perms.createPermission(CreatePermissionRequest.newBuilder().setName("SONARR").build());
    assertEquals(
        4, perms.listPermissions(ListPermissionsRequest.getDefaultInstance()).getPermissionsCount());
    assertStatus(
        Status.Code.ALREADY_EXISTS,
        "409",
        () -> perms.createPermission(CreatePermissionRequest.newBuilder().setName("SONARR").build()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> perms.createPermission(CreatePermissionRequest.newBuilder().setName("bad").build()));

    var granted =
        perms.grantUserPermission(
            UserPermissionRequest.newBuilder().setUsername("carol").setPermission("RADARR").build());
    assertTrue(granted.getPermissionsList().contains("RADARR"));

    var set =
        perms.setUserPermissions(
            SetUserPermissionsRequest.newBuilder()
                .setUsername("carol")
                .setPermissions(PermissionList.newBuilder().addValues("AI_USER"))
                .build());
    assertEquals(List.of("AI_USER"), set.getPermissionsList());
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> perms.setUserPermissions(SetUserPermissionsRequest.newBuilder().setUsername("carol").build()));

    var revoked =
        perms.revokeUserPermission(
            UserPermissionRequest.newBuilder().setUsername("carol").setPermission("AI_USER").build());
    assertTrue(revoked.getPermissionsList().isEmpty());

    assertStatus(
        Status.Code.UNAUTHENTICATED,
        "401",
        () ->
            PermissionServiceGrpc.newBlockingStub(channel)
                .listPermissions(ListPermissionsRequest.getDefaultInstance()));
  }

  @Test
  void node_enrollRegisterHeartbeatAndDelete() {
    String token = saveUser("dave", "USER");
    var nodes = NodeServiceGrpc.newBlockingStub(channel);

    var enrollment =
        withToken(nodes, token)
            .createEnrollmentToken(CreateEnrollmentTokenRequest.newBuilder().setLabel("rig").build());
    var registered =
        nodes.registerNode(
            RegisterNodeRequest.newBuilder()
                .setToken(enrollment.getToken())
                .setName("rig")
                .addModels("qwen3:14b")
                .setVramGb(24)
                .build());

    nodes.heartbeat(
        HeartbeatRequest.newBuilder()
            .setNodeId(registered.getNodeId())
            .setNodeKey(registered.getNodeKey())
            .setStatus("busy")
            .setModels(ModelList.newBuilder().addValues("llama3:8b"))
            .build());
    var listed = nodes.listNodes(ListNodesRequest.getDefaultInstance()).getNodes(0);
    assertEquals(NodeStatus.NODE_STATUS_BUSY, listed.getStatus());
    assertEquals(List.of("llama3:8b"), listed.getModelsList());
    assertEquals(24.0, listed.getVramGb());

    assertStatus(
        Status.Code.UNAUTHENTICATED,
        "401",
        () ->
            nodes.heartbeat(
                HeartbeatRequest.newBuilder()
                    .setNodeId(registered.getNodeId())
                    .setNodeKey("wrong")
                    .build()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> nodes.heartbeat(HeartbeatRequest.newBuilder().setNodeId(registered.getNodeId()).build()));

    nodes.deleteNode(
        DeleteNodeRequest.newBuilder()
            .setNodeId(registered.getNodeId())
            .setNodeKey(registered.getNodeKey())
            .build());
    assertEquals(0, nodes.listNodes(ListNodesRequest.getDefaultInstance()).getNodesCount());
    assertStatus(
        Status.Code.UNAUTHENTICATED,
        "401",
        () -> nodes.createEnrollmentToken(CreateEnrollmentTokenRequest.getDefaultInstance()));
  }

  private void saveChatNode() {
    aiNodeRepository.save(
        AiNode.builder()
            .id("node-1")
            .name("test-node")
            .status(org.fitznet.fitznetapi.model.NodeStatus.ONLINE)
            .models(List.of("qwen3:14b"))
            .address("127.0.0.1:" + fakeOllama.getAddress().getPort())
            .build());
  }

  @Test
  void chatStream_streamsEvents() {
    saveChatNode();
    String token = saveUser("chatter", "USER", "AI_USER");
    var events = new ArrayList<ChatEvent>();
    withToken(NodeServiceGrpc.newBlockingStub(channel), token)
        .chatStream(
            ChatRequest.newBuilder()
                .setNodeId("node-1")
                .addMessages(ChatMessage.newBuilder().setRole("user").setContent("hi"))
                .build())
        .forEachRemaining(events::add);

    assertEquals(ChatEvent.EventCase.META, events.get(0).getEventCase());
    assertEquals("qwen3:14b", events.get(0).getMeta().getModel());
    assertEquals("pong", events.get(1).getToken().getContent());
    var done = events.get(events.size() - 1);
    assertEquals(ChatEvent.EventCase.DONE, done.getEventCase());
    assertEquals(3, done.getDone().getPromptTokens());
  }

  @Test
  void chat_enforcesPermissionsAndValidation() {
    saveChatNode();
    String plain = saveUser("plain", "USER");
    String chatter = saveUser("chatter", "USER", "AI_USER");
    var nodes = NodeServiceGrpc.newBlockingStub(channel);
    ChatRequest hi = ChatRequest.newBuilder().setNodeId("node-1").setPrompt("hi").build();

    assertStatus(Status.Code.UNAUTHENTICATED, "401", () -> nodes.chatStream(hi).hasNext());
    assertStatus(
        Status.Code.PERMISSION_DENIED, "403", () -> withToken(nodes, plain).chatStream(hi).hasNext());
    assertStatus(
        Status.Code.PERMISSION_DENIED, "403", () -> withToken(nodes, plain).chat(hi));
    assertStatus(
        Status.Code.NOT_FOUND,
        "404",
        () -> withToken(nodes, chatter).chatStream(hi.toBuilder().setNodeId("nope").build()).hasNext());
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> withToken(nodes, chatter).chatStream(hi.toBuilder().clearPrompt().build()).hasNext());
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () ->
            withToken(nodes, chatter)
                .chat(
                    hi.toBuilder()
                        .addMessages(ChatMessage.newBuilder().setRole("robot").setContent("x"))
                        .build()));
  }
}
