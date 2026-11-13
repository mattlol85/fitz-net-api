package org.fitznet.fitznetapi.integration;

import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.LLAMA;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.MAPPER;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.VISION;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.png;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import java.util.ArrayList;
import java.util.List;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.grpc.proto.AiChatServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.CreateConversationRequest;
import org.fitznet.fitznetapi.grpc.proto.CreatePresetRequest;
import org.fitznet.fitznetapi.grpc.proto.DeletePresetRequest;
import org.fitznet.fitznetapi.grpc.proto.GetAttachmentRequest;
import org.fitznet.fitznetapi.grpc.proto.GetConversationRequest;
import org.fitznet.fitznetapi.grpc.proto.ListPresetsRequest;
import org.fitznet.fitznetapi.grpc.proto.ModelTarget;
import org.fitznet.fitznetapi.grpc.proto.SendMessageRequest;
import org.fitznet.fitznetapi.grpc.proto.TurnEvent;
import org.fitznet.fitznetapi.grpc.proto.UpdatePresetRequest;
import org.fitznet.fitznetapi.grpc.proto.UploadAttachmentRequest;
import org.fitznet.fitznetapi.model.AiAttachment;
import org.fitznet.fitznetapi.repository.AiAttachmentRepository;
import org.fitznet.fitznetapi.repository.AiConversationRepository;
import org.fitznet.fitznetapi.repository.AiMessageRepository;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.repository.AiPresetRepository;
import org.fitznet.fitznetapi.repository.AiUserPrefsRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.ai.FakeOllama;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The preset and attachment RPCs of {@code AiChatService} over a real socket. */
@SpringBootTest
@Import(EmbeddedMongoTestConfiguration.class)
class AiChatPresetAttachmentGrpcIntegrationTest {

  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> HTTP_STATUS =
      Metadata.Key.of("x-http-status", Metadata.ASCII_STRING_MARSHALLER);

  @LocalGrpcServerPort private int port;
  @Autowired private UserRepository userRepository;
  @Autowired private AiNodeRepository aiNodeRepository;
  @Autowired private AiConversationRepository conversations;
  @Autowired private AiMessageRepository messages;
  @Autowired private AiPresetRepository presets;
  @Autowired private AiAttachmentRepository attachments;
  @Autowired private AiUserPrefsRepository prefs;
  @Autowired private JwtUtil jwtUtil;

  private ManagedChannel channel;
  private FakeOllama alpha;
  private String nodeA;
  private String alice;
  private String bob;
  private String admin;
  private String plain;

  @BeforeEach
  void setUp() throws Exception {
    channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    clean();
    alice = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "alice", "USER", "AI_USER");
    bob = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "bob", "USER", "AI_USER");
    admin = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "admin", "USER", "ADMIN");
    plain = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "plain", "USER");
    alpha = AiConversationTestSupport.withModels(new FakeOllama());
    nodeA = AiConversationTestSupport.uniqueNodeId();
    aiNodeRepository.save(AiConversationTestSupport.node(nodeA, "Alpha", alpha.address()));
  }

  @AfterEach
  void tearDown() {
    channel.shutdownNow();
    alpha.close();
    clean();
  }

  private void clean() {
    userRepository.deleteAll();
    conversations.deleteAll();
    messages.deleteAll();
    presets.deleteAll();
    attachments.deleteAll();
    prefs.deleteAll();
    aiNodeRepository.deleteAll();
  }

  private <S extends AbstractStub<S>> S withToken(S stub, String token) {
    Metadata headers = new Metadata();
    headers.put(AUTHORIZATION, "Bearer " + token);
    return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  private AiChatServiceGrpc.AiChatServiceBlockingStub as(String token) {
    return withToken(AiChatServiceGrpc.newBlockingStub(channel), token);
  }

  private static void assertStatus(Status.Code code, String httpStatus, Executable call) {
    StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, call);
    assertEquals(code, ex.getStatus().getCode(), ex.getStatus().toString());
    assertEquals(httpStatus, ex.getTrailers().get(HTTP_STATUS));
  }

  private UploadAttachmentRequest upload(String mimeType, byte[] data) {
    return UploadAttachmentRequest.newBuilder()
        .setName("shot")
        .setMimeType(mimeType)
        .setData(ByteString.copyFrom(data))
        .build();
  }

  // ---- presets -----------------------------------------------------------------------------

  @Test
  void aiPreset_roundTrip() {
    var chat = as(alice);

    var created =
        chat.createPreset(
            CreatePresetRequest.newBuilder()
                .setName("Code reviewer")
                .setDescription("Blunt")
                .setModel("qwen2.5-coder:14b")
                .setNodeId("n1")
                .setSystemPrompt("You are...")
                .setTemperature(0.2)
                .setNumCtx(8192)
                .build());
    assertFalse(created.getId().isEmpty());
    assertEquals("Code reviewer", created.getName());
    assertEquals("qwen2.5-coder:14b", created.getModel());
    assertEquals("n1", created.getNodeId());
    assertEquals(0.2, created.getTemperature());
    assertEquals(8192, created.getNumCtx());
    assertEquals("private", created.getVisibility());
    assertEquals("alice", created.getOwnerUsername());
    assertTrue(created.getEditable());
    assertFalse(created.hasRequiredPermission());
    assertTrue(created.hasCreatedAt());

    var listed = chat.listPresets(ListPresetsRequest.getDefaultInstance());
    assertEquals(1, listed.getItemsCount());
    assertEquals(created.getId(), listed.getItems(0).getId());

    var updated =
        chat.updatePreset(
            UpdatePresetRequest.newBuilder().setPresetId(created.getId()).setName("Renamed").build());
    assertEquals("Renamed", updated.getName());
    assertFalse(updated.hasModel(), "an update replaces the whole preset");
    assertFalse(updated.hasNumCtx());

    assertTrue(
        chat.deletePreset(DeletePresetRequest.newBuilder().setPresetId(created.getId()).build())
            .getDeleted());
    assertEquals(0, chat.listPresets(ListPresetsRequest.getDefaultInstance()).getItemsCount());
  }

  @Test
  void aiPreset_enforcesPermissionsAndVisibility() {
    var anonymous = AiChatServiceGrpc.newBlockingStub(channel);
    var mine =
        as(alice).createPreset(CreatePresetRequest.newBuilder().setName("mine").build());
    var shared =
        as(admin)
            .createPreset(CreatePresetRequest.newBuilder().setName("shared").setVisibility("shared").build());

    assertStatus(
        Status.Code.UNAUTHENTICATED,
        "401",
        () -> anonymous.listPresets(ListPresetsRequest.getDefaultInstance()));
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        "403",
        () -> as(plain).listPresets(ListPresetsRequest.getDefaultInstance()));
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        "403",
        () -> as(plain).createPreset(CreatePresetRequest.newBuilder().setName("x").build()));
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        "403",
        () ->
            as(alice)
                .createPreset(
                    CreatePresetRequest.newBuilder().setName("x").setVisibility("shared").build()));
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        "403",
        () ->
            as(alice)
                .deletePreset(DeletePresetRequest.newBuilder().setPresetId(shared.getId()).build()));
    assertStatus(
        Status.Code.NOT_FOUND,
        "404",
        () ->
            as(bob)
                .deletePreset(DeletePresetRequest.newBuilder().setPresetId(mine.getId()).build()));
    assertStatus(
        Status.Code.NOT_FOUND,
        "404",
        () ->
            as(admin)
                .updatePreset(
                    UpdatePresetRequest.newBuilder().setPresetId(mine.getId()).setName("x").build()));

    var bobsView = as(bob).listPresets(ListPresetsRequest.getDefaultInstance());
    assertEquals(1, bobsView.getItemsCount());
    assertEquals("shared", bobsView.getItems(0).getName());
    assertFalse(bobsView.getItems(0).getEditable());
    assertTrue(
        as(admin).listPresets(ListPresetsRequest.getDefaultInstance()).getItems(0).getEditable());
  }

  @Test
  void aiPreset_validatesRequests() {
    var chat = as(alice);

    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> chat.createPreset(CreatePresetRequest.getDefaultInstance()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () ->
            chat.createPreset(CreatePresetRequest.newBuilder().setName("n").setTemperature(3.0).build()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> chat.createPreset(CreatePresetRequest.newBuilder().setName("n").setNumCtx(100).build()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> chat.createPreset(CreatePresetRequest.newBuilder().setName("n").setNodeId("n1").build()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () ->
            chat.createPreset(
                CreatePresetRequest.newBuilder().setName("n").setVisibility("everyone").build()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> chat.deletePreset(DeletePresetRequest.getDefaultInstance()));
    assertEquals(0, presets.count());
  }

  // ---- attachments -------------------------------------------------------------------------

  @Test
  void aiAttachment_uploadAndDownloadRoundTrip() {
    byte[] data = png(4000);

    var uploaded = as(alice).uploadAttachment(upload("image/png", data));

    assertFalse(uploaded.getId().isEmpty());
    assertEquals("image", uploaded.getKind());
    assertEquals("image/png", uploaded.getMimeType());
    assertEquals(4000, uploaded.getSizeBytes());
    assertEquals("shot", uploaded.getName());
    AiAttachment stored = attachments.findById(uploaded.getId()).orElseThrow();
    assertTrue(stored.getExpiresAt() != null && stored.getMessageId() == null);

    var content =
        as(alice)
            .getAttachment(GetAttachmentRequest.newBuilder().setAttachmentId(uploaded.getId()).build());
    assertEquals("image/png", content.getMimeType());
    assertArrayEquals(data, content.getData().toByteArray());
  }

  @Test
  void aiAttachment_validatesAndScopesToTheOwner() {
    var uploaded = as(alice).uploadAttachment(upload("image/png", png(100)));

    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> as(alice).uploadAttachment(upload("image/gif", png(100))));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> as(alice).uploadAttachment(upload("image/png", "not an image".getBytes())));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> as(alice).uploadAttachment(upload("image/png", new byte[0])));
    assertStatus(
        Status.Code.NOT_FOUND,
        "404",
        () ->
            as(bob)
                .getAttachment(GetAttachmentRequest.newBuilder().setAttachmentId(uploaded.getId()).build()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> as(alice).getAttachment(GetAttachmentRequest.getDefaultInstance()));
    assertStatus(
        Status.Code.UNAUTHENTICATED,
        "401",
        () -> AiChatServiceGrpc.newBlockingStub(channel).uploadAttachment(upload("image/png", png(100))));
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        "403",
        () -> as(plain).uploadAttachment(upload("image/png", png(100))));
    assertEquals(1, attachments.count());
  }

  @Test
  void aiAttachment_sendMessageWithAnImageReachesTheVisionModel() throws Exception {
    byte[] data = png(900);
    var uploaded = as(alice).uploadAttachment(upload("image/png", data));
    String conversationId =
        as(alice)
            .createConversation(
                CreateConversationRequest.newBuilder()
                    .setTarget(ModelTarget.newBuilder().setModel(VISION).setNodeId(nodeA))
                    .build())
            .getId();

    List<TurnEvent> events = new ArrayList<>();
    as(alice)
        .sendMessage(
            SendMessageRequest.newBuilder()
                .setConversationId(conversationId)
                .setContent("what is this?")
                .addAttachmentIds(uploaded.getId())
                .build())
        .forEachRemaining(events::add);

    assertEquals(TurnEvent.EventCase.DONE, events.get(events.size() - 1).getEventCase());
    var sent = MAPPER.readTree(alpha.chatBodies().get(0)).path("messages").get(0);
    assertEquals(1, sent.path("images").size());
    assertEquals(
        java.util.Base64.getEncoder().encodeToString(data), sent.path("images").get(0).asText());
    var detail =
        as(alice).getConversation(GetConversationRequest.newBuilder().setConversationId(conversationId).build());
    assertEquals(1, detail.getMessages(0).getAttachmentsCount());
    assertEquals(uploaded.getId(), detail.getMessages(0).getAttachments(0).getId());
    assertTrue(detail.getMessages(1).getGeneration().hasContextOverflow());
    assertFalse(detail.getMessages(1).getGeneration().getContextOverflow());
    assertTrue(attachments.findById(uploaded.getId()).orElseThrow().getExpiresAt() == null);
  }

  @Test
  void aiAttachment_aNonVisionModelAnswersModelNotVisionWithAMinimalGeneration() {
    var uploaded = as(alice).uploadAttachment(upload("image/png", png(300)));
    String conversationId =
        as(alice)
            .createConversation(
                CreateConversationRequest.newBuilder()
                    .setTarget(ModelTarget.newBuilder().setModel(LLAMA).setNodeId(nodeA))
                    .build())
            .getId();

    List<TurnEvent> events = new ArrayList<>();
    as(alice)
        .sendMessage(
            SendMessageRequest.newBuilder()
                .setConversationId(conversationId)
                .setContent("see")
                .addAttachmentIds(uploaded.getId())
                .build())
        .forEachRemaining(events::add);

    assertEquals(1, events.size());
    assertEquals("MODEL_NOT_VISION", events.get(0).getError().getCode());
    var detail =
        as(alice).getConversation(GetConversationRequest.newBuilder().setConversationId(conversationId).build());
    var generation = detail.getMessages(1).getGeneration();
    assertEquals(LLAMA, generation.getModel());
    assertEquals(nodeA, generation.getNodeId());
    assertEquals("error", generation.getFinishReason());
    assertFalse(generation.hasPromptTokens());
    assertFalse(generation.hasContextOverflow());
  }

  @Test
  void aiAttachment_anUnknownIdOnASendIsAnInvalidArgument() {
    String conversationId =
        as(alice)
            .createConversation(
                CreateConversationRequest.newBuilder()
                    .setTarget(ModelTarget.newBuilder().setModel(VISION).setNodeId(nodeA))
                    .build())
            .getId();

    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () ->
            as(alice)
                .sendMessage(
                    SendMessageRequest.newBuilder()
                        .setConversationId(conversationId)
                        .setContent("x")
                        .addAttachmentIds("000000000000000000000000")
                        .build())
                .hasNext());
  }
}
