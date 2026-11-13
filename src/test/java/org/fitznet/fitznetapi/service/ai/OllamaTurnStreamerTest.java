package org.fitznet.fitznetapi.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.fitznet.fitznetapi.service.ChatEventSink;
import org.fitznet.fitznetapi.service.ai.TurnResult.Ended;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

class OllamaTurnStreamerTest {

  private static final Duration POLL = Duration.ofMillis(200);

  private final ObjectMapper mapper = new ObjectMapper();
  private final NodeActivityTracker tracker = new NodeActivityTracker();

  private HttpServer fakeOllama;
  private SilentUpstream silent;
  private volatile String receivedBody;

  @AfterEach
  void tearDown() throws IOException {
    if (fakeOllama != null) {
      fakeOllama.stop(0);
    }
    if (silent != null) {
      silent.close();
    }
    MDC.clear();
  }

  // ---- sink --------------------------------------------------------------------------------

  private record Event(String name, Map<String, ?> data) {}

  /** Records what the engine writes; can be told the client is gone. */
  private static final class RecordingSink implements ChatEventSink {
    final List<Event> events = new CopyOnWriteArrayList<>();
    final AtomicInteger keepAlives = new AtomicInteger();
    final AtomicInteger concurrent = new AtomicInteger();
    final AtomicBoolean overlapped = new AtomicBoolean();
    final AtomicReference<String> keepAliveCorrelationId = new AtomicReference<>();
    volatile boolean clientGone;
    volatile boolean failOnToken;
    volatile long writeDelayMillis;
    volatile java.util.function.Consumer<Event> onEvent = e -> {};

    @Override
    public void event(String name, Map<String, ?> data) {
      enter();
      try {
        if (clientGone || (failOnToken && name.equals("token"))) {
          throw new UncheckedIOException(new IOException("Broken pipe"));
        }
        Event event = new Event(name, data);
        events.add(event);
        onEvent.accept(event);
      } finally {
        exit();
      }
    }

    @Override
    public void keepAlive() {
      enter();
      try {
        if (clientGone) {
          throw new UncheckedIOException(new IOException("gRPC client cancelled the call"));
        }
        keepAlives.incrementAndGet();
        keepAliveCorrelationId.compareAndSet(null, MDC.get("correlationId"));
      } finally {
        exit();
      }
    }

    private void enter() {
      if (concurrent.incrementAndGet() > 1) {
        overlapped.set(true);
      }
      if (writeDelayMillis > 0) {
        try {
          Thread.sleep(writeDelayMillis);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private void exit() {
      concurrent.decrementAndGet();
    }

    List<String> names() {
      return events.stream().map(Event::name).toList();
    }

    List<Event> named(String name) {
      return events.stream().filter(e -> e.name().equals(name)).toList();
    }

    List<String> phases() {
      return named("status").stream().map(e -> String.valueOf(e.data().get("phase"))).toList();
    }
  }

  // ---- fixtures ----------------------------------------------------------------------------

  private OllamaTurnStreamer streamer(Duration readTimeout, Duration maxStreamDuration) {
    return new OllamaTurnStreamer(
        mapper, tracker, Duration.ofSeconds(2), readTimeout, maxStreamDuration, "45m", POLL);
  }

  private OllamaTurnStreamer streamer() {
    return streamer(Duration.ofSeconds(5), Duration.ofMinutes(1));
  }

  private RoutedTarget target(String address, boolean loaded) {
    return new RoutedTarget(
        "node-1", "Matt-PC", address, "qwen3:14b", false, List.of("completion"), 8192, loaded);
  }

  private RoutedTarget target(boolean loaded) {
    return target("127.0.0.1:" + fakeOllama.getAddress().getPort(), loaded);
  }

  private TurnRequest request(RoutedTarget target) {
    return new TurnRequest(
        target, List.of(TurnMessage.of("user", "hi")), null, 8192, null, false, 0);
  }

  private void startOllama(HttpHandler handler) throws IOException {
    fakeOllama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fakeOllama.createContext(
        "/api/chat",
        exchange -> {
          receivedBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          handler.handle(exchange);
        });
    fakeOllama.setExecutor(Executors.newCachedThreadPool());
    fakeOllama.start();
  }

  private static void respondNdjson(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** Emits a token every 20ms until the API side hangs up, then counts down. */
  private static void streamForever(HttpExchange exchange, CountDownLatch upstreamDropped)
      throws IOException {
    exchange.sendResponseHeaders(200, 0);
    OutputStream out = exchange.getResponseBody();
    byte[] chunk =
        "{\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"done\":false}\n"
            .getBytes(StandardCharsets.UTF_8);
    try {
      for (int i = 0; i < 1_000; i++) {
        out.write(chunk);
        out.flush();
        Thread.sleep(20);
      }
    } catch (IOException dropped) {
      upstreamDropped.countDown();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  /**
   * A raw TCP "Ollama" that reads the request and then says nothing (optionally after sending the
   * response headers), and notices the moment the API side closes the socket.
   */
  private static final class SilentUpstream implements AutoCloseable {
    private final ServerSocket server;
    final CountDownLatch requestRead = new CountDownLatch(1);
    final CountDownLatch dropped = new CountDownLatch(1);
    volatile long droppedAtNanos;

    SilentUpstream(boolean sendHeaders) throws IOException {
      server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
      Thread thread =
          new Thread(
              () -> {
                try (Socket socket = server.accept()) {
                  InputStream in = socket.getInputStream();
                  readRequest(in);
                  requestRead.countDown();
                  if (sendHeaders) {
                    OutputStream out = socket.getOutputStream();
                    out.write(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nTransfer-Encoding: chunked\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                  }
                  while (in.read() != -1) {
                    // wait for EOF: the API dropped the connection
                  }
                } catch (IOException dropped) {
                  // reset counts as dropped too
                } finally {
                  droppedAtNanos = System.nanoTime();
                  dropped.countDown();
                }
              },
              "silent-upstream");
      thread.setDaemon(true);
      thread.start();
    }

    String address() {
      return "127.0.0.1:" + server.getLocalPort();
    }

    private static void readRequest(InputStream in) throws IOException {
      ByteArrayOutputStream head = new ByteArrayOutputStream();
      int b;
      while ((b = in.read()) != -1) {
        head.write(b);
        byte[] bytes = head.toByteArray();
        int n = bytes.length;
        if (n >= 4
            && bytes[n - 4] == 13
            && bytes[n - 3] == 10
            && bytes[n - 2] == 13
            && bytes[n - 1] == 10) {
          break;
        }
      }
      String headers = head.toString(StandardCharsets.US_ASCII).toLowerCase();
      int at = headers.indexOf("content-length:");
      if (at >= 0) {
        int end = headers.indexOf("\r\n", at);
        int length = Integer.parseInt(headers.substring(at + 15, end).trim());
        in.readNBytes(length);
      }
    }

    @Override
    public void close() throws IOException {
      server.close();
    }
  }

  // ---- events, stats, request body ---------------------------------------------------------

  private static final String THINKING_STREAM =
      """
      {"message":{"role":"assistant","content":"","thinking":"Let me"},"done":false}
      {"message":{"role":"assistant","content":"","thinking":" think"},"done":false}
      {"message":{"role":"assistant","content":"Hi"},"done":false}
      {"message":{"role":"assistant","content":" there"},"done":false}
      {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":42,"eval_count":50,"eval_duration":1000000000,"load_duration":2000000000}
      """;

  @Test
  void shouldRelayThinkingAndTokenDeltasWithStatusPhasesAndStats() throws Exception {
    startOllama(
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          OutputStream out = exchange.getResponseBody();
          for (String line : THINKING_STREAM.split("\n")) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
              Thread.sleep(30);
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
            }
          }
          exchange.close();
        });
    RecordingSink sink = new RecordingSink();

    TurnResult result = streamer().stream(request(target(false)), sink);

    assertEquals(
        List.of("status", "status", "thinking", "thinking", "token", "token"), sink.names());
    assertEquals(List.of("loading_model", "generating"), sink.phases());
    Map<String, ?> loading = sink.named("status").get(0).data();
    assertEquals("node-1", loading.get("nodeId"));
    assertEquals("qwen3:14b", loading.get("model"));
    assertEquals("Loading qwen3:14b on Matt-PC…", loading.get("message"));
    assertEquals("Let me", sink.named("thinking").get(0).data().get("content"));
    assertEquals(" there", sink.named("token").get(1).data().get("content"));

    assertEquals(Ended.COMPLETED, result.ended());
    assertEquals("Hi there", result.content());
    assertEquals("Let me think", result.thinking());
    assertEquals(42, result.promptTokens());
    assertEquals(50, result.completionTokens());
    assertEquals("stop", result.finishReason());
    assertNull(result.error());
    assertTrue(result.upstreamStarted());
    TurnResult.TurnStats stats = result.stats();
    assertEquals(2000, stats.loadMs());
    assertEquals(50.0, stats.tokensPerSecond());
    assertNotNull(stats.ttftMs());
    assertNotNull(stats.thinkingMs());
    assertTrue(stats.thinkingMs() >= 20, "thinking lasted " + stats.thinkingMs());
    assertTrue(stats.ttftMs() <= stats.totalMs());
  }

  @Test
  void shouldSkipTheLoadingStatusWhenTheModelIsAlreadyLoaded() throws Exception {
    startOllama(exchange -> respondNdjson(exchange, THINKING_STREAM));
    RecordingSink sink = new RecordingSink();

    streamer().stream(request(target(true)), sink);

    assertEquals(List.of("generating"), sink.phases());
  }

  @Test
  void shouldReportNoThinkingWhenTheModelDoesNotThink() throws Exception {
    startOllama(
        exchange ->
            respondNdjson(
                exchange,
                """
                {"message":{"role":"assistant","content":"Hello"},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}
                """));

    TurnResult result = streamer().stream(request(target(true)), new RecordingSink());

    assertNull(result.thinking());
    assertNull(result.stats().thinkingMs());
    assertNull(result.stats().tokensPerSecond(), "no eval figures were reported");
    assertEquals(0, result.stats().loadMs());
    assertNull(result.promptTokens());
  }

  @Test
  void shouldMapDoneReasonLengthToLength() throws Exception {
    startOllama(
        exchange ->
            respondNdjson(
                exchange,
                """
                {"message":{"role":"assistant","content":"cut"},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"done_reason":"length","prompt_eval_count":8000,"eval_count":192}
                """));

    TurnResult result = streamer().stream(request(target(true)), new RecordingSink());

    assertEquals("length", result.finishReason());
    assertEquals(8000, result.promptTokens());
  }

  @Test
  void shouldSendThinkTrueOnlyWhenAsked() throws Exception {
    startOllama(exchange -> respondNdjson(exchange, THINKING_STREAM));
    RoutedTarget target = target(true);

    streamer().stream(request(target), new RecordingSink());
    assertFalse(receivedBody.contains("\"think\""), receivedBody);

    streamer()
        .stream(
            new TurnRequest(
                target, List.of(TurnMessage.of("user", "hi")), null, 8192, null, true, 0),
            new RecordingSink());
    assertTrue(receivedBody.contains("\"think\":true"), receivedBody);
  }

  @Test
  void shouldSendKeepAliveNumCtxTemperatureSystemPromptAndImages() throws Exception {
    startOllama(exchange -> respondNdjson(exchange, "{\"done\":true}\n"));
    TurnRequest request =
        new TurnRequest(
            target(true),
            List.of(
                TurnMessage.of("user", "I am Bob"),
                TurnMessage.of("assistant", "Hi Bob"),
                new TurnMessage("user", "What is this?", List.of("aGVsbG8="))),
            "Be terse.",
            4096,
            0.2,
            false,
            0);

    streamer().stream(request, new RecordingSink());

    assertTrue(receivedBody.contains("\"stream\":true"), receivedBody);
    assertTrue(receivedBody.contains("\"keep_alive\":\"45m\""), receivedBody);
    assertTrue(receivedBody.contains("\"num_ctx\":4096"), receivedBody);
    assertTrue(receivedBody.contains("\"temperature\":0.2"), receivedBody);
    assertTrue(receivedBody.contains("\"images\":[\"aGVsbG8=\"]"), receivedBody);
    int system = receivedBody.indexOf("Be terse.");
    int first = receivedBody.indexOf("I am Bob");
    int second = receivedBody.indexOf("Hi Bob");
    int third = receivedBody.indexOf("What is this?");
    assertTrue(system >= 0 && system < first && first < second && second < third, receivedBody);
    assertTrue(receivedBody.indexOf("\"role\":\"system\"") < first, receivedBody);
  }

  @Test
  void shouldLeaveTemperatureOutWhenNotSet() throws Exception {
    startOllama(exchange -> respondNdjson(exchange, "{\"done\":true}\n"));

    streamer().stream(request(target(true)), new RecordingSink());

    assertFalse(receivedBody.contains("temperature"), receivedBody);
  }

  // ---- failures ----------------------------------------------------------------------------

  @Test
  void shouldReportAnUpstreamErrorLineBeforeAnyChunkAsNodeError() throws Exception {
    startOllama(exchange -> respondNdjson(exchange, "{\"error\":\"model not found\"}\n"));
    RecordingSink sink = new RecordingSink();

    TurnResult result = streamer().stream(request(target(true)), sink);

    assertEquals(Ended.ERROR, result.ended());
    assertEquals(ChatErrorCodes.NODE_ERROR, result.error().code());
    assertTrue(result.error().message().contains("model not found"));
    assertFalse(result.upstreamStarted(), "an error line is not a chunk");
    assertEquals("error", result.finishReason());
    assertTrue(sink.events.isEmpty(), "the engine writes no error event itself");
  }

  @Test
  void shouldKeepPartialContentWhenTheNodeFailsMidStream() throws Exception {
    startOllama(
        exchange ->
            respondNdjson(
                exchange,
                """
                {"message":{"role":"assistant","content":"par"},"done":false}
                {"message":{"role":"assistant","content":"tial"},"done":false}
                {"error":"out of memory"}
                """));

    TurnResult result = streamer().stream(request(target(true)), new RecordingSink());

    assertEquals(Ended.ERROR, result.ended());
    assertEquals("partial", result.content());
    assertTrue(result.upstreamStarted());
    assertNull(result.promptTokens());
  }

  @Test
  void shouldReportAnUpstreamHttpErrorAsNodeErrorBeforeAnyChunk() throws Exception {
    startOllama(
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });

    TurnResult result = streamer().stream(request(target(true)), new RecordingSink());

    assertEquals(Ended.ERROR, result.ended());
    assertEquals(ChatErrorCodes.NODE_ERROR, result.error().code());
    assertEquals("Node returned HTTP 500", result.error().message());
    assertFalse(result.upstreamStarted());
  }

  @Test
  void shouldReportNodeUnreachableWhenNothingListens() throws Exception {
    startOllama(exchange -> respondNdjson(exchange, "{\"done\":true}\n"));
    RoutedTarget dead = target(true);
    fakeOllama.stop(0);

    TurnResult result = streamer().stream(request(dead), new RecordingSink());

    assertEquals(Ended.ERROR, result.ended());
    assertEquals(ChatErrorCodes.NODE_UNREACHABLE, result.error().code());
    assertFalse(result.upstreamStarted());
  }

  @Test
  void shouldReportNodeUnreachableWhenTheStreamEndsBeforeDone() throws Exception {
    startOllama(
        exchange ->
            respondNdjson(
                exchange, "{\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"done\":false}\n"));

    TurnResult result = streamer().stream(request(target(true)), new RecordingSink());

    assertEquals(ChatErrorCodes.NODE_UNREACHABLE, result.error().code());
    assertEquals("x", result.content());
    assertTrue(result.upstreamStarted());
  }

  @Test
  void shouldTimeOutWhenTheNodeGoesSilentForTheReadTimeout() throws Exception {
    silent = new SilentUpstream(true);

    TurnResult result =
        streamer(Duration.ofMillis(300), Duration.ofMinutes(1))
            .stream(request(target(silent.address(), true)), new RecordingSink());

    assertEquals(Ended.ERROR, result.ended());
    assertEquals(ChatErrorCodes.TIMEOUT, result.error().code());
    assertFalse(result.upstreamStarted());
    assertTrue(silent.dropped.await(5, TimeUnit.SECONDS));
  }

  @Test
  void shouldEndWithTimeoutAndDropUpstreamWhenMaxStreamDurationIsExceeded() throws Exception {
    CountDownLatch upstreamDropped = new CountDownLatch(1);
    startOllama(exchange -> streamForever(exchange, upstreamDropped));

    TurnResult result =
        streamer(Duration.ofSeconds(5), Duration.ofMillis(300))
            .stream(request(target(true)), new RecordingSink());

    assertEquals(ChatErrorCodes.TIMEOUT, result.error().code());
    assertTrue(upstreamDropped.await(5, TimeUnit.SECONDS), "Ollama connection was not dropped");
  }

  @Test
  void shouldEnforceMaxStreamDurationEvenWhileTheNodeIsSilent() throws Exception {
    silent = new SilentUpstream(true);
    long started = System.nanoTime();

    TurnResult result =
        streamer(Duration.ofSeconds(30), Duration.ofMillis(400))
            .stream(request(target(silent.address(), true)), new RecordingSink());

    assertEquals(ChatErrorCodes.TIMEOUT, result.error().code());
    assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5);
    assertTrue(silent.dropped.await(5, TimeUnit.SECONDS));
  }

  // ---- cancel ------------------------------------------------------------------------------

  @Test
  void shouldCancelAndDropUpstreamWhenAClientWriteFailsMidStream() throws Exception {
    CountDownLatch upstreamDropped = new CountDownLatch(1);
    startOllama(exchange -> streamForever(exchange, upstreamDropped));
    RecordingSink sink = new RecordingSink();
    sink.failOnToken = true;

    TurnResult result = streamer().stream(request(target(true)), sink);

    assertEquals(Ended.CANCELLED, result.ended());
    assertEquals("cancelled", result.finishReason());
    assertNull(result.error());
    assertTrue(upstreamDropped.await(5, TimeUnit.SECONDS), "Ollama connection was not dropped");
  }

  @Test
  void shouldKeepThePartialReplyWhenTheClientCancelsMidStream() throws Exception {
    CountDownLatch upstreamDropped = new CountDownLatch(1);
    startOllama(exchange -> streamForever(exchange, upstreamDropped));
    RecordingSink sink = new RecordingSink();
    AtomicInteger tokens = new AtomicInteger();
    sink.onEvent =
        event -> {
          if (event.name().equals("token") && tokens.incrementAndGet() == 3) {
            sink.clientGone = true;
          }
        };

    TurnResult result = streamer().stream(request(target(true)), sink);

    assertEquals(Ended.CANCELLED, result.ended());
    assertTrue(result.content().startsWith("xxx"), result.content());
    assertTrue(result.upstreamStarted());
    assertNull(result.promptTokens());
  }

  @Test
  void shouldDropUpstreamWithinThePollIntervalWhenCancelledWhileTheModelIsSilent()
      throws Exception {
    // No bytes at all from the node (model still loading): only the ticker can notice the cancel.
    silent = new SilentUpstream(false);
    RecordingSink sink = new RecordingSink();
    AtomicReference<TurnResult> result = new AtomicReference<>();
    Thread turn =
        new Thread(
            () -> result.set(streamer().stream(request(target(silent.address(), false)), sink)));
    turn.start();
    assertTrue(silent.requestRead.await(5, TimeUnit.SECONDS));
    long cancelledAt = System.nanoTime();
    sink.clientGone = true;

    assertTrue(silent.dropped.await(5, TimeUnit.SECONDS), "upstream connection was not dropped");
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(silent.droppedAtNanos - cancelledAt);
    turn.join(5_000);

    // One poll interval (200 ms) plus scheduling slack.
    assertTrue(elapsedMillis <= POLL.toMillis() + 600, "dropped after " + elapsedMillis + " ms");
    assertEquals(Ended.CANCELLED, result.get().ended());
    assertFalse(result.get().upstreamStarted());
    assertEquals("", result.get().content());
  }

  @Test
  void shouldDropUpstreamWithinThePollIntervalWhenCancelledAfterHeadersButBeforeAnyChunk()
      throws Exception {
    silent = new SilentUpstream(true);
    RecordingSink sink = new RecordingSink();
    AtomicReference<TurnResult> result = new AtomicReference<>();
    Thread turn =
        new Thread(
            () -> result.set(streamer().stream(request(target(silent.address(), true)), sink)));
    turn.start();
    assertTrue(silent.requestRead.await(5, TimeUnit.SECONDS));
    Thread.sleep(100); // let the reader block on the empty body
    long cancelledAt = System.nanoTime();
    sink.clientGone = true;

    assertTrue(silent.dropped.await(5, TimeUnit.SECONDS), "upstream connection was not dropped");
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(silent.droppedAtNanos - cancelledAt);
    turn.join(5_000);

    assertTrue(elapsedMillis <= POLL.toMillis() + 600, "dropped after " + elapsedMillis + " ms");
    assertEquals(Ended.CANCELLED, result.get().ended());
  }

  @Test
  void shouldWriteKeepAlivesWhileTheNodeIsSilentAndStopAfterwards() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    startOllama(
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          OutputStream out = exchange.getResponseBody();
          out.write("{\"message\":{\"content\":\"late\"},\"done\":true}\n".getBytes(StandardCharsets.UTF_8));
          exchange.close();
        });
    RecordingSink sink = new RecordingSink();
    Thread releaser =
        new Thread(
            () -> {
              try {
                Thread.sleep(900);
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              }
              release.countDown();
            });
    releaser.start();

    TurnResult result = streamer().stream(request(target(true)), sink);

    assertEquals(Ended.COMPLETED, result.ended());
    assertEquals("late", result.content());
    assertTrue(sink.keepAlives.get() >= 3, "keep-alives: " + sink.keepAlives.get());
    int afterReturn = sink.keepAlives.get();
    Thread.sleep(500);
    assertEquals(afterReturn, sink.keepAlives.get(), "the ticker must stop with the turn");
  }

  @Test
  void shouldSerialiseSinkWritesBetweenTheTickerAndTheReader() throws Exception {
    startOllama(
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          OutputStream out = exchange.getResponseBody();
          for (int i = 0; i < 40; i++) {
            out.write(
                "{\"message\":{\"content\":\"t\"},\"done\":false}\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
              Thread.sleep(5);
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
            }
          }
          out.write("{\"done\":true}\n".getBytes(StandardCharsets.UTF_8));
          exchange.close();
        });
    RecordingSink sink = new RecordingSink();
    sink.writeDelayMillis = 15;
    OllamaTurnStreamer fastTicker =
        new OllamaTurnStreamer(
            mapper,
            tracker,
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofMinutes(1),
            "30m",
            Duration.ofMillis(2));

    TurnResult result = fastTicker.stream(request(target(true)), sink);

    assertEquals(Ended.COMPLETED, result.ended());
    assertEquals("t".repeat(40), result.content());
    assertFalse(sink.overlapped.get(), "the sink was written from two threads at once");
    assertTrue(sink.keepAlives.get() > 0, "the ticker never ran");
  }

  @Test
  void shouldCarryTheCorrelationIdOntoTheTickerThread() throws Exception {
    silent = new SilentUpstream(true);
    MDC.put("correlationId", "trace-77");
    RecordingSink sink = new RecordingSink();

    streamer(Duration.ofMillis(700), Duration.ofMinutes(1))
        .stream(request(target(silent.address(), true)), sink);

    assertTrue(sink.keepAlives.get() >= 1);
    assertEquals("trace-77", sink.keepAliveCorrelationId.get());
  }

  // ---- in-flight tracking ------------------------------------------------------------------

  @Test
  void shouldCountTheTurnAsInFlightWhileRunningAndReleaseItAfterwards() throws Exception {
    startOllama(exchange -> respondNdjson(exchange, THINKING_STREAM));
    RecordingSink sink = new RecordingSink();
    AtomicInteger activeDuringTurn = new AtomicInteger(-1);
    sink.onEvent = event -> activeDuringTurn.set(tracker.active("node-1"));

    streamer().stream(request(target(true)), sink);

    assertEquals(1, activeDuringTurn.get());
    assertEquals(0, tracker.active("node-1"));
  }

  @Test
  void shouldReleaseTheInFlightCountAfterAnErrorAndAfterACancel() throws Exception {
    startOllama(
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    streamer().stream(request(target(true)), new RecordingSink());
    assertEquals(0, tracker.active("node-1"));

    CountDownLatch upstreamDropped = new CountDownLatch(1);
    fakeOllama.stop(0);
    startOllama(exchange -> streamForever(exchange, upstreamDropped));
    RecordingSink sink = new RecordingSink();
    sink.failOnToken = true;
    streamer().stream(request(target(true)), sink);
    assertEquals(0, tracker.active("node-1"));
  }

  // ---- Ollama's error body -----------------------------------------------------------------

  private static void respondStatus(HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Test
  void shouldUseOllamasErrorTextWhenAnHttpErrorCarriesAJsonBody() throws Exception {
    startOllama(
        exchange ->
            respondStatus(
                exchange,
                500,
                "{\"error\":\"model requires more system memory (8.0 GiB) than is available\"}"));

    TurnResult result = streamer().stream(request(target(true)), new RecordingSink());

    assertEquals(Ended.ERROR, result.ended());
    assertEquals(ChatErrorCodes.NODE_ERROR, result.error().code());
    assertEquals(
        "Node error: model requires more system memory (8.0 GiB) than is available",
        result.error().message());
    assertFalse(result.upstreamStarted());
  }

  @Test
  void shouldTruncateALongOllamaErrorTextToThreeHundredCharacters() throws Exception {
    startOllama(
        exchange -> respondStatus(exchange, 500, "{\"error\":\"" + "e".repeat(1500) + "\"}"));

    TurnResult result = streamer().stream(request(target(true)), new RecordingSink());

    assertEquals("Node error: " + "e".repeat(300), result.error().message());
  }

  @Test
  void shouldFallBackToTheStatusWhenTheErrorBodyIsNotJsonHasNoErrorOrIsHuge() throws Exception {
    String[] bodies = {
      "<html>bad gateway</html>",
      "{\"message\":\"no error key\"}",
      "{\"error\":42}",
      "{\"error\":\"" + "x".repeat(5000) + "\"}", // longer than the bounded read: cut off, unparsable
      ""
    };
    for (String body : bodies) {
      startOllama(exchange -> respondStatus(exchange, 503, body));

      TurnResult result = streamer().stream(request(target(true)), new RecordingSink());

      assertEquals("Node returned HTTP 503", result.error().message(), body.length() + " byte body");
      fakeOllama.stop(0);
    }
  }
}
