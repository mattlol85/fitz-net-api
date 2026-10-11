package org.fitznet.fitznetapi.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OllamaChatStreamerTest {

  private HttpServer fakeOllama;
  private String receivedBody;

  @AfterEach
  void stopServer() {
    if (fakeOllama != null) {
      fakeOllama.stop(0);
    }
  }

  @Test
  void stream_relaysTokensAsSseAndFinishesWithDone() throws Exception {
    startOllama(
        exchange ->
            respondNdjson(
                exchange,
                """
                {"message":{"role":"assistant","content":""},"done":false}
                {"message":{"role":"assistant","content":"Hello"},"done":false}
                {"message":{"role":"assistant","content":" world"},"done":false}
                {"message":{"role":"assistant","content":""},"done":true}
                """));

    String sse = runStream(streamer(Duration.ofSeconds(5), Duration.ofMinutes(1)));

    String meta = sse.substring(0, sse.indexOf("\n\n") + 2);
    assertTrue(meta.startsWith("event: meta\ndata: {"));
    assertTrue(meta.contains("\"model\":\"qwen3:14b\"") && meta.contains("\"nodeId\":\"node-1\""));
    assertEquals(
        ":\n\n"
            + "event: token\ndata: {\"content\":\"Hello\"}\n\n"
            + "event: token\ndata: {\"content\":\" world\"}\n\n"
            + ":\n\n"
            + "event: done\ndata: {}\n\n",
        sse.substring(meta.length()));
    assertTrue(receivedBody.contains("\"stream\":true"));
    assertTrue(receivedBody.contains("\"model\":\"qwen3:14b\""));
  }

  @Test
  void stream_upstreamErrorLine_emitsErrorEvent() throws Exception {
    startOllama(exchange -> respondNdjson(exchange, "{\"error\":\"model not found\"}\n"));

    String sse = runStream(streamer(Duration.ofSeconds(5), Duration.ofMinutes(1)));

    assertTrue(sse.endsWith("event: error\ndata: {\"message\":\"Node error: model not found\"}\n\n"));
  }

  @Test
  void stream_upstreamHttpError_emitsErrorEvent() throws Exception {
    startOllama(
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });

    String sse = runStream(streamer(Duration.ofSeconds(5), Duration.ofMinutes(1)));

    assertTrue(sse.endsWith("event: error\ndata: {\"message\":\"Node returned HTTP 500\"}\n\n"));
  }

  @Test
  void stream_upstreamGoesSilent_emitsTimeoutError() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    startOllama(
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          awaitQuietly(release);
          exchange.close();
        });

    String sse = runStream(streamer(Duration.ofMillis(200), Duration.ofMinutes(1)));
    release.countDown();

    assertTrue(sse.contains("event: error"));
    assertTrue(sse.contains("Node stopped responding"));
  }

  @Test
  void stream_exceedsMaxDuration_emitsErrorAndDropsUpstream() throws Exception {
    CountDownLatch upstreamDropped = new CountDownLatch(1);
    startOllama(exchange -> streamForever(exchange, upstreamDropped));

    String sse = runStream(streamer(Duration.ofSeconds(5), Duration.ofMillis(300)));

    assertTrue(sse.contains("Generation exceeded the"));
    assertFalse(sse.contains("event: done"));
    assertTrue(upstreamDropped.await(5, TimeUnit.SECONDS), "Ollama connection was not dropped");
  }

  @Test
  void stream_clientDisconnects_dropsUpstream() throws Exception {
    CountDownLatch upstreamDropped = new CountDownLatch(1);
    startOllama(exchange -> streamForever(exchange, upstreamDropped));
    OutputStream brokenClient =
        new OutputStream() {
          private int writes;

          @Override
          public void write(int b) {}

          @Override
          public void flush() throws IOException {
            if (++writes > 3) {
              throw new IOException("Broken pipe");
            }
          }
        };

    streamer(Duration.ofSeconds(5), Duration.ofMinutes(1)).stream(target(), "hi", brokenClient);

    assertTrue(upstreamDropped.await(5, TimeUnit.SECONDS), "Ollama connection was not dropped");
  }

  private OllamaChatStreamer streamer(Duration readTimeout, Duration maxStreamDuration) {
    return new OllamaChatStreamer(
        new ObjectMapper(), Duration.ofSeconds(2), readTimeout, maxStreamDuration);
  }

  private ChatTarget target() {
    return new ChatTarget("node-1", "127.0.0.1:" + fakeOllama.getAddress().getPort(), "qwen3:14b");
  }

  private String runStream(OllamaChatStreamer streamer) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    streamer.stream(target(), "hi", out);
    return out.toString(StandardCharsets.UTF_8);
  }

  private void startOllama(HttpHandler handler) throws IOException {
    fakeOllama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    fakeOllama.createContext(
        "/api/chat",
        exchange -> {
          receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          handler.handle(exchange);
        });
    fakeOllama.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
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

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
