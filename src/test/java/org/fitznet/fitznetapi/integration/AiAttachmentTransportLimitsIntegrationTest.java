package org.fitznet.fitznetapi.integration;

import static org.fitznet.fitznetapi.integration.AiConversationTestSupport.png;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.grpc.proto.AiChatServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.GetAttachmentRequest;
import org.fitznet.fitznetapi.grpc.proto.UploadAttachmentRequest;
import org.fitznet.fitznetapi.repository.AiAttachmentRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A 3 MB image must pass both real transports: Tomcat (a ~4.2 MB JSON body) and gRPC (one 3 MB
 * message). Runs against real sockets, not MockMvc, so the servers' own size limits apply.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EmbeddedMongoTestConfiguration.class)
class AiAttachmentTransportLimitsIntegrationTest {

  private static final int MAX_BYTES = 3_145_728;

  @Value("${local.server.port}")
  private int httpPort;

  @LocalGrpcServerPort private int grpcPort;
  @Autowired private UserRepository userRepository;
  @Autowired private AiAttachmentRepository attachments;
  @Autowired private JwtUtil jwtUtil;

  private final HttpClient http = HttpClient.newHttpClient();
  private ManagedChannel channel;
  private String token;

  @BeforeEach
  void setUp() {
    userRepository.deleteAll();
    attachments.deleteAll();
    token = AiConversationTestSupport.saveUser(userRepository, jwtUtil, "alice", "USER", "AI_USER");
    channel = ManagedChannelBuilder.forAddress("localhost", grpcPort).usePlaintext().build();
  }

  @AfterEach
  void tearDown() {
    channel.shutdownNow();
    attachments.deleteAll();
  }

  private HttpResponse<String> postJson(String dataBase64) throws Exception {
    String body =
        "{\"name\":\"big.png\",\"mimeType\":\"image/png\",\"dataBase64\":\"" + dataBase64 + "\"}";
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + httpPort + "/ai/attachments"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private AiChatServiceGrpc.AiChatServiceBlockingStub grpc() {
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
    return AiChatServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  private static UploadAttachmentRequest upload(byte[] data) {
    return UploadAttachmentRequest.newBuilder()
        .setName("big.png")
        .setMimeType("image/png")
        .setData(ByteString.copyFrom(data))
        .build();
  }

  // ---- REST over Tomcat --------------------------------------------------------------------

  @Test
  void shouldAcceptAnImageAtTheLimitOverRestEvenThoughTheJsonBodyIsAboutFourPointTwoMegabytes()
      throws Exception {
    String base64 = Base64.getEncoder().encodeToString(png(MAX_BYTES));
    assertEquals(4_194_304, base64.length());

    HttpResponse<String> response = postJson(base64);

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, attachments.count());
  }

  @Test
  void shouldAnswer400ForAnImageJustOverTheLimitOverRest() throws Exception {
    HttpResponse<String> response =
        postJson(Base64.getEncoder().encodeToString(png(MAX_BYTES + 1)));

    assertEquals(400, response.statusCode(), response.body());
    assertEquals(0, attachments.count());
  }

  @Test
  void shouldAnswer400RatherThanDroppingTheConnectionForAMuchLargerRestBody() throws Exception {
    HttpResponse<String> response =
        postJson(Base64.getEncoder().encodeToString(png(9_000_000)));

    assertEquals(400, response.statusCode(), response.body());
  }

  // ---- gRPC --------------------------------------------------------------------------------

  @Test
  void shouldAcceptAnImageAtTheLimitOverGrpcAndReadItBack() {
    byte[] data = png(MAX_BYTES);

    var uploaded = grpc().uploadAttachment(upload(data));

    assertEquals(MAX_BYTES, uploaded.getSizeBytes());
    var content =
        grpc().getAttachment(GetAttachmentRequest.newBuilder().setAttachmentId(uploaded.getId()).build());
    assertArrayEquals(data, content.getData().toByteArray());
  }

  @Test
  void shouldAnswerInvalidArgumentForAnImageJustOverTheLimitOverGrpc() {
    StatusRuntimeException ex =
        assertThrows(
            StatusRuntimeException.class, () -> grpc().uploadAttachment(upload(png(MAX_BYTES + 1))));

    assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode(), ex.getStatus().toString());
    assertEquals(0, attachments.count());
  }

  @Test
  void shouldStillReachTheValidationForAMessageLargerThanTheGrpcDefaultInboundLimit() {
    // 5 MB is above gRPC's 4 MiB default, so this only gets a validation answer because the
    // inbound limit was raised explicitly (spring.grpc.server.max-inbound-message-size).
    StatusRuntimeException ex =
        assertThrows(
            StatusRuntimeException.class, () -> grpc().uploadAttachment(upload(png(5_000_000))));

    assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode(), ex.getStatus().toString());
  }
}
