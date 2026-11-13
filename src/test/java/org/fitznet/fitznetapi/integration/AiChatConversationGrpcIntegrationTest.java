package org.fitznet.fitznetapi.integration;

import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.LLAMA;
import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.MetadataUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.grpc.proto.AiChatServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.ConversationSettings;
import org.fitznet.fitznetapi.grpc.proto.CreateConversationRequest;
import org.fitznet.fitznetapi.grpc.proto.DeleteConversationRequest;
import org.fitznet.fitznetapi.grpc.proto.EditMessageRequest;
import org.fitznet.fitznetapi.grpc.proto.GenerateTitleRequest;
import org.fitznet.fitznetapi.grpc.proto.GetContextUsageRequest;
import org.fitznet.fitznetapi.grpc.proto.GetConversationRequest;
import org.fitznet.fitznetapi.grpc.proto.ListConversationsRequest;
import org.fitznet.fitznetapi.grpc.proto.ModelTarget;
import org.fitznet.fitznetapi.grpc.proto.RegenerateMessageRequest;
import org.fitznet.fitznetapi.grpc.proto.SendMessageRequest;
import org.fitznet.fitznetapi.grpc.proto.SummarizeConversationRequest;
import org.fitznet.fitznetapi.grpc.proto.TurnEvent;
import org.fitznet.fitznetapi.grpc.proto.UpdateConversationRequest;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiMessage;
import org.fitznet.fitznetapi.repository.AiConversationRepository;
import org.fitznet.fitznetapi.repository.AiMessageRepository;
import org.fitznet.fitznetapi.repository.AiNodeRepository;
import org.fitznet.fitznetapi.repository.AiUserPrefsRepository;
import org.fitznet.fitznetapi.repository.PermissionRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.ai.ConversationAccess;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** The conversation, context and turn RPCs of {@code AiChatService} over a real socket. */
@SpringBootTest
@Import(EmbeddedMongoTestConfiguration.class)
class AiChatConversationGrpcIntegrationTest {

  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> HTTP_STATUS =
      Metadata.Key.of("x-http-status", Metadata.ASCII_STRING_MARSHALLER);

  @LocalGrpcServerPort private int port;
  @Autowired private UserRepository userRepository;
  @Autowired private PermissionRepository permissionRepository;
  @Autowired private AiNodeRepository aiNodeRepository;
  @Autowired private AiConversationRepository conversations;
  @Autowired private AiMessageRepository messages;
  @Autowired private AiUserPrefsRepository prefs;
  @Autowired private JwtUtil jwtUtil;

  @MockitoSpyBean private ConversationAccess access;

  private ManagedChannel channel;
  private FakeOllama alpha;
  private String nodeA;
  private String alice;
  private String bob;
  private String plain;

  @BeforeEach
  void setUp() throws Exception {
    channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    userRepository.deleteAll();
    conversations.deleteAll();
    messages.deleteAll();
    prefs.deleteAll();
    aiNodeRepository.deleteAll();
    alice = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "alice", "USER", "AI_USER");
    bob = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "bob", "USER", "AI_USER");
    plain = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "plain", "USER");
    alpha = AiConversationTestSupport.withModels(new FakeOllama());
    nodeA = AiConversationTestSupport.uniqueNodeId();
    aiNodeRepository.save(AiConversationTestSupport.node(nodeA, "Alpha", alpha.address()));
  }

  @AfterEach
  void tearDown() {
    channel.shutdownNow();
    alpha.close();
    conversations.deleteAll();
    messages.deleteAll();
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

  private String newConversation(String token) {
    return as(token)
        .createConversation(
            CreateConversationRequest.newBuilder()
                .setTarget(ModelTarget.newBuilder().setModel(LLAMA).setNodeId(nodeA))
                .build())
        .getId();
  }

  private List<TurnEvent> send(String token, String conversationId, String content) {
    List<TurnEvent> events = new ArrayList<>();
    as(token)
        .sendMessage(
            SendMessageRequest.newBuilder()
                .setConversationId(conversationId)
                .setContent(content)
                .build())
        .forEachRemaining(events::add);
    return events;
  }

  // ---- conversations -----------------------------------------------------------------------

  @Test
  void aiConversation_roundTrip() {
    var chat = as(alice);

    var created =
        chat.createConversation(
            CreateConversationRequest.newBuilder()
                .setTarget(ModelTarget.newBuilder().setModel(LLAMA).setNodeId(nodeA))
                .setSettings(ConversationSettings.newBuilder().setSystemPrompt("Be terse"))
                .build());
    assertFalse(created.getId().isEmpty());
    assertEquals("New chat", created.getTitle());
    assertEquals("default", created.getTitleSource());
    assertEquals(LLAMA, created.getTarget().getModel());
    assertEquals(nodeA, created.getTarget().getNodeId());
    assertEquals("Be terse", created.getSettings().getSystemPrompt());
    assertTrue(created.hasCreatedAt());

    var listed = chat.listConversations(ListConversationsRequest.getDefaultInstance());
    assertEquals(1, listed.getItemsCount());
    assertEquals(created.getId(), listed.getItems(0).getId());
    assertFalse(listed.getHasMore());
    assertFalse(listed.getItems(0).hasMatchSnippet());

    var detail =
        chat.getConversation(GetConversationRequest.newBuilder().setConversationId(created.getId()).build());
    assertEquals(created.getId(), detail.getId());
    assertEquals(0, detail.getMessagesCount());
    assertTrue(detail.hasContext());
    assertEquals(8192, detail.getContext().getContextLength());
    assertTrue(detail.getContext().getFits());

    var pinned =
        chat.updateConversation(
            UpdateConversationRequest.newBuilder()
                .setConversationId(created.getId())
                .setPinned(true)
                .build());
    assertTrue(pinned.getPinned());
    assertEquals(LLAMA, pinned.getTarget().getModel(), "an unset target is left alone");
    assertEquals("Be terse", pinned.getSettings().getSystemPrompt());

    var renamed =
        chat.updateConversation(
            UpdateConversationRequest.newBuilder()
                .setConversationId(created.getId())
                .setTitle("Renamed")
                .setSettings(ConversationSettings.getDefaultInstance())
                .setTarget(ModelTarget.getDefaultInstance())
                .build());
    assertEquals("Renamed", renamed.getTitle());
    assertEquals("user", renamed.getTitleSource());
    assertFalse(renamed.hasTarget(), "an empty ModelTarget clears the target");
    assertFalse(renamed.getSettings().hasSystemPrompt(), "a set settings message replaces both");

    var untouched =
        chat.generateTitle(GenerateTitleRequest.newBuilder().setConversationId(created.getId()).build());
    assertEquals("Renamed", untouched.getTitle());

    assertTrue(
        chat.deleteConversation(
                DeleteConversationRequest.newBuilder().setConversationId(created.getId()).build())
            .getDeleted());
    assertStatus(
        Status.Code.NOT_FOUND,
        "404",
        () ->
            chat.getConversation(
                GetConversationRequest.newBuilder().setConversationId(created.getId()).build()));
  }

  @Test
  void aiConversation_searchReturnsASnippetForContentHits() {
    AiConversation conversation =
        AiConversationTestSupport.seedConversation(conversations, "alice", "Unrelated", null);
    AiConversationTestSupport.seedUser(
        conversations, messages, conversation, "the quick brown fox");

    var listed =
        as(alice).listConversations(ListConversationsRequest.newBuilder().setQ("BROWN").build());

    assertEquals(1, listed.getItemsCount());
    assertTrue(listed.getItems(0).getMatchSnippet().contains("brown fox"));
  }

  @Test
  void aiConversation_isolatesUsers() {
    String id = newConversation(alice);

    assertStatus(
        Status.Code.NOT_FOUND,
        "404",
        () -> as(bob).getConversation(GetConversationRequest.newBuilder().setConversationId(id).build()));
    assertStatus(
        Status.Code.NOT_FOUND,
        "404",
        () ->
            as(bob)
                .updateConversation(
                    UpdateConversationRequest.newBuilder()
                        .setConversationId(id)
                        .setTitle("x")
                        .build()));
    assertStatus(
        Status.Code.NOT_FOUND,
        "404",
        () ->
            as(bob)
                .deleteConversation(
                    DeleteConversationRequest.newBuilder().setConversationId(id).build()));
    assertStatus(
        Status.Code.NOT_FOUND,
        "404",
        () ->
            as(bob)
                .sendMessage(
                    SendMessageRequest.newBuilder().setConversationId(id).setContent("hi").build())
                .hasNext());
    assertEquals(0, as(bob).listConversations(ListConversationsRequest.getDefaultInstance()).getItemsCount());
  }

  @Test
  void aiConversation_enforcesPermissions() {
    var anonymous = AiChatServiceGrpc.newBlockingStub(channel);
    var forbidden = as(plain);
    SendMessageRequest send =
        SendMessageRequest.newBuilder().setConversationId("x").setContent("hi").build();

    assertStatus(
        Status.Code.UNAUTHENTICATED,
        "401",
        () -> anonymous.listConversations(ListConversationsRequest.getDefaultInstance()));
    assertStatus(Status.Code.UNAUTHENTICATED, "401", () -> anonymous.sendMessage(send).hasNext());
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        "403",
        () -> forbidden.listConversations(ListConversationsRequest.getDefaultInstance()));
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        "403",
        () -> forbidden.createConversation(CreateConversationRequest.getDefaultInstance()));
    assertStatus(Status.Code.PERMISSION_DENIED, "403", () -> forbidden.sendMessage(send).hasNext());
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        "403",
        () ->
            forbidden.getContextUsage(
                GetContextUsageRequest.newBuilder().setConversationId("x").build()));
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        "403",
        () ->
            forbidden.summarizeConversation(
                SummarizeConversationRequest.newBuilder().setConversationId("x").build()));
  }

  @Test
  void aiConversation_validatesRequests() {
    String id = newConversation(alice);
    var chat = as(alice);

    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> chat.getConversation(GetConversationRequest.getDefaultInstance()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () ->
            chat.createConversation(
                CreateConversationRequest.newBuilder().setTitle("t".repeat(121)).build()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () ->
            chat.updateConversation(
                UpdateConversationRequest.newBuilder()
                    .setConversationId(id)
                    .setTitle("  ")
                    .build()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () ->
            chat.sendMessage(
                    SendMessageRequest.newBuilder().setConversationId(id).setContent("  ").build())
                .hasNext());
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () ->
            chat.sendMessage(
                    SendMessageRequest.newBuilder()
                        .setConversationId(id)
                        .setContent("hi")
                        .setTarget(ModelTarget.newBuilder().setNodeId(nodeA))
                        .build())
                .hasNext());
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () ->
            chat.regenerateMessage(
                    RegenerateMessageRequest.newBuilder().setConversationId(id).build())
                .hasNext());
  }

  @Test
  void aiConversation_contextAndSummarizeMapTheirHttpStatuses() {
    AiConversation noTarget = AiConversationTestSupport.seedConversation(conversations, "alice", "c", null);
    String id = newConversation(alice);
    var chat = as(alice);

    assertStatus(
        Status.Code.UNAVAILABLE,
        "503",
        () ->
            chat.getContextUsage(
                GetContextUsageRequest.newBuilder().setConversationId(noTarget.getId()).build()));
    assertStatus(
        Status.Code.ALREADY_EXISTS,
        "409",
        () ->
            chat.summarizeConversation(
                SummarizeConversationRequest.newBuilder().setConversationId(id).build()));
    var usage =
        chat.getContextUsage(
            GetContextUsageRequest.newBuilder()
                .setConversationId(id)
                .setModel(AiConversationTestSupport.TINY)
                .setNodeId(nodeA)
                .build());
    assertEquals(1024, usage.getContextLength());
    assertEquals(nodeA, usage.getNodeId());
  }

  // ---- turns -------------------------------------------------------------------------------

  @Test
  void aiTurn_sendMessageStreamsEventsEndToEnd() {
    String id = newConversation(alice);

    List<TurnEvent> events = send(alice, id, "hello");

    assertEquals(
        List.of(
            TurnEvent.EventCase.META,
            TurnEvent.EventCase.STATUS,
            TurnEvent.EventCase.STATUS,
            TurnEvent.EventCase.TOKEN,
            TurnEvent.EventCase.TOKEN,
            TurnEvent.EventCase.DONE),
        events.stream().map(TurnEvent::getEventCase).toList());
    var meta = events.get(0).getMeta();
    assertEquals(id, meta.getConversationId());
    assertFalse(meta.getUserMessageId().isEmpty());
    assertFalse(meta.getAssistantMessageId().isEmpty());
    assertEquals(nodeA, meta.getNodeId());
    assertEquals("Alpha", meta.getNodeName());
    assertEquals(LLAMA, meta.getModel());
    assertFalse(meta.getAuto());
    assertFalse(meta.hasPresetId());
    assertEquals(8192, meta.getContextLength());
    assertEquals(0, meta.getContext().getTrimmedCount());
    assertEquals("loading_model", events.get(1).getStatus().getPhase());
    assertEquals(nodeA, events.get(1).getStatus().getNodeId());
    assertEquals("generating", events.get(2).getStatus().getPhase());
    assertEquals("Hello", events.get(3).getToken().getContent());
    assertEquals(" there", events.get(4).getToken().getContent());
    var done = events.get(5).getDone();
    assertEquals(meta.getAssistantMessageId(), done.getMessageId());
    assertEquals(10, done.getPromptTokens());
    assertEquals(2, done.getCompletionTokens());
    assertEquals("stop", done.getFinishReason());
    assertFalse(done.getContextOverflow());
    assertEquals(2.0, done.getStats().getTokensPerSecond());
    assertTrue(done.getStats().getTotalMs() >= 0);

    var detail = as(alice).getConversation(GetConversationRequest.newBuilder().setConversationId(id).build());
    assertEquals(2, detail.getMessagesCount());
    assertEquals("Hello there", detail.getMessages(1).getContent());
    assertEquals("complete", detail.getMessages(1).getStatus());
    assertEquals(nodeA, detail.getMessages(1).getGeneration().getNodeId());
    assertEquals(10, detail.getMessages(1).getGeneration().getPromptTokens());
    assertEquals("hello", detail.getTitle());
  }

  @Test
  void aiTurn_statsClockStartsBeforeAnyMongoCallOfTheGrpcMethod() {
    String id = newConversation(alice);
    AtomicBoolean slowed = new AtomicBoolean();
    doAnswer(
            invocation -> {
              if (slowed.compareAndSet(false, true)) {
                Thread.sleep(300);
              }
              return invocation.callRealMethod();
            })
        .when(access)
        .owned(any(), any());

    List<TurnEvent> events = send(alice, id, "hello");

    var stats = events.get(events.size() - 1).getDone().getStats();
    assertTrue(stats.getTotalMs() >= 300, "total_ms " + stats.getTotalMs());
    assertTrue(stats.getTtftMs() >= 300, "ttft_ms " + stats.getTtftMs());
  }

  @Test
  void aiTurn_secondSendReplaysStoredHistory() throws Exception {
    String id = newConversation(alice);
    send(alice, id, "My name is Bob");
    send(alice, id, "Who am I?");

    var sent = MAPPER.readTree(alpha.chatBodies().get(1)).path("messages");

    assertEquals(3, sent.size());
    assertEquals("My name is Bob", sent.get(0).path("content").asText());
    assertEquals("Hello there", sent.get(1).path("content").asText());
    assertEquals("Who am I?", sent.get(2).path("content").asText());
  }

  @Test
  void aiTurn_routingFailureEndsWithAnErrorEventThatListsAlternatives() {
    String id =
        as(alice)
            .createConversation(
                CreateConversationRequest.newBuilder()
                    .setTarget(ModelTarget.newBuilder().setModel("ghost:7b").setNodeId(nodeA))
                    .build())
            .getId();

    List<TurnEvent> events = send(alice, id, "hi");

    assertEquals(1, events.size());
    var error = events.get(0).getError();
    assertEquals("MODEL_NOT_ON_NODE", error.getCode());
    assertEquals(0, error.getAlternativesCount());
    assertFalse(error.getMessageId().isEmpty());
  }

  @Test
  void aiTurn_regenerateAndEditStreamToo() {
    String id = newConversation(alice);
    send(alice, id, "question");
    var stored =
        as(alice).getConversation(GetConversationRequest.newBuilder().setConversationId(id).build());
    String userId = stored.getMessages(0).getId();
    String assistantId = stored.getMessages(1).getId();

    List<TurnEvent> regenerated = new ArrayList<>();
    as(alice)
        .regenerateMessage(
            RegenerateMessageRequest.newBuilder()
                .setConversationId(id)
                .setMessageId(assistantId)
                .build())
        .forEachRemaining(regenerated::add);
    assertEquals(TurnEvent.EventCase.DONE, regenerated.get(regenerated.size() - 1).getEventCase());
    assertFalse(assistantId.equals(regenerated.get(0).getMeta().getAssistantMessageId()));

    List<TurnEvent> edited = new ArrayList<>();
    as(alice)
        .editMessage(
            EditMessageRequest.newBuilder()
                .setConversationId(id)
                .setMessageId(userId)
                .setContent("edited")
                .build())
        .forEachRemaining(edited::add);
    assertEquals(TurnEvent.EventCase.DONE, edited.get(edited.size() - 1).getEventCase());
    assertEquals(userId, edited.get(0).getMeta().getUserMessageId());
    var after =
        as(alice).getConversation(GetConversationRequest.newBuilder().setConversationId(id).build());
    assertEquals(2, after.getMessagesCount());
    assertEquals("edited", after.getMessages(0).getContent());
    assertTrue(after.getMessages(0).hasEditedAt());
  }

  @Test
  void aiTurn_secondTurnWhileOneRunsIsAnAlreadyExistsError() throws Exception {
    alpha.holdChat();
    String id = newConversation(alice);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread first =
        new Thread(
            () -> {
              try {
                send(alice, id, "slow");
              } catch (RuntimeException ex) {
                failure.set(ex);
              }
            });
    first.start();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (alpha.chatCalls.get() < 1 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }

    assertStatus(Status.Code.ALREADY_EXISTS, "409", () -> send(alice, id, "second"));

    alpha.releaseChat();
    first.join(10_000);
    assertEquals(null, failure.get());
  }

  @Test
  void aiTurn_cancellingTheCallMidStreamDropsTheUpstreamAndKeepsACancelledPartial()
      throws Exception {
    alpha.chatForever();
    String id = newConversation(alice);
    CountDownLatch tokens = new CountDownLatch(3);
    CountDownLatch ended = new CountDownLatch(1);
    AtomicReference<ClientCallStreamObserver<SendMessageRequest>> call = new AtomicReference<>();

    withToken(AiChatServiceGrpc.newStub(channel), alice)
        .sendMessage(
            SendMessageRequest.newBuilder().setConversationId(id).setContent("go").build(),
            new ClientResponseObserver<SendMessageRequest, TurnEvent>() {
              @Override
              public void beforeStart(ClientCallStreamObserver<SendMessageRequest> requestStream) {
                call.set(requestStream);
              }

              @Override
              public void onNext(TurnEvent event) {
                if (event.hasToken()) {
                  tokens.countDown();
                }
              }

              @Override
              public void onError(Throwable error) {
                ended.countDown();
              }

              @Override
              public void onCompleted() {
                ended.countDown();
              }
            });

    assertTrue(tokens.await(10, TimeUnit.SECONDS), "no tokens arrived");
    call.get().cancel("test cancel", null);

    assertTrue(alpha.chatDropped.await(5, TimeUnit.SECONDS), "Ollama connection was not dropped");
    assertTrue(ended.await(5, TimeUnit.SECONDS));
    AiMessage assistant = null;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      List<AiMessage> stored = messages.findByConversationIdOrderBySeqAsc(id);
      if (stored.size() == 2 && !AiMessage.STATUS_STREAMING.equals(stored.get(1).getStatus())) {
        assistant = stored.get(1);
        break;
      }
      Thread.sleep(20);
    }
    assertTrue(assistant != null, "the assistant message was never finalised");
    assertEquals(AiMessage.STATUS_CANCELLED, assistant.getStatus());
    assertTrue(assistant.getContent().startsWith("xxx"), assistant.getContent());
    assertEquals("cancelled", assistant.getGeneration().finishReason());
  }
}
