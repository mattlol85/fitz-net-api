package org.fitznet.fitznetapi.service.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake node Ollama on 127.0.0.1 (random port): {@code /api/tags}, {@code /api/ps},
 * {@code /api/show}, {@code /api/generate} and {@code /api/chat} with configurable answers and
 * call counters. {@code /api/chat} streams the configured NDJSON lines (or tokens forever), can be held
 * on a gate, and answers non-streaming calls (titles, summaries) with a configurable reply.
 */
public final class FakeOllama implements AutoCloseable {

  private final HttpServer server;
  private final java.util.concurrent.atomic.AtomicBoolean closed =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final Map<String, String> showByModel = new ConcurrentHashMap<>();
  private final List<String> generateBodies = new CopyOnWriteArrayList<>();
  private final List<String> chatBodies = new CopyOnWriteArrayList<>();

  public final AtomicInteger tagsCalls = new AtomicInteger();
  public final AtomicInteger psCalls = new AtomicInteger();
  public final AtomicInteger showCalls = new AtomicInteger();
  public final AtomicInteger generateCalls = new AtomicInteger();
  public final AtomicInteger chatCalls = new AtomicInteger();
  /** Counts down when a streaming chat connection is dropped by the API side. */
  public volatile CountDownLatch chatDropped = new CountDownLatch(1);

  private volatile String tagsJson = "{\"models\":[]}";
  private volatile String psJson = "{\"models\":[]}";
  private volatile int generateStatus = 200;
  private volatile CountDownLatch generateGate = new CountDownLatch(0);
  private volatile CountDownLatch chatGate = new CountDownLatch(0);
  private volatile List<String> chatLines =
      List.of(chunk("Hello"), chunk(" there"), doneChunk(10, 2));
  private volatile boolean chatForever;
  private volatile int chatStatus = 200;
  private volatile long chatLineDelayMillis;
  private volatile String completionReply = "A Nice Title";
  private volatile int completionStatus = 200;

  public FakeOllama() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/api/tags",
        exchange -> {
          tagsCalls.incrementAndGet();
          respond(exchange, 200, tagsJson);
        });
    server.createContext(
        "/api/ps",
        exchange -> {
          psCalls.incrementAndGet();
          respond(exchange, 200, psJson);
        });
    server.createContext(
        "/api/show",
        exchange -> {
          showCalls.incrementAndGet();
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String answer = null;
          for (Map.Entry<String, String> entry : showByModel.entrySet()) {
            if (body.contains("\"" + entry.getKey() + "\"")) {
              answer = entry.getValue();
            }
          }
          if (answer == null) {
            respond(exchange, 404, "{\"error\":\"model not found\"}");
          } else {
            respond(exchange, 200, answer);
          }
        });
    server.createContext(
        "/api/generate",
        exchange -> {
          generateBodies.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          generateCalls.incrementAndGet();
          try {
            generateGate.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          respond(exchange, generateStatus, "{\"done\":true,\"done_reason\":\"load\"}");
        });
    server.createContext(
        "/api/chat",
        exchange -> {
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          chatBodies.add(body);
          chatCalls.incrementAndGet();
          if (body.contains("\"stream\":false")) {
            respond(
                exchange,
                completionStatus,
                "{\"message\":{\"role\":\"assistant\",\"content\":\""
                    + completionReply.replace("\"", "\\\"")
                    + "\"},\"done\":true}");
            return;
          }
          try {
            chatGate.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
          if (chatStatus != 200) {
            respond(exchange, chatStatus, "{\"error\":\"boom\"}");
            return;
          }
          exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
          exchange.sendResponseHeaders(200, 0);
          OutputStream out = exchange.getResponseBody();
          try {
            if (chatForever) {
              byte[] line = (chunk("x") + "\n").getBytes(StandardCharsets.UTF_8);
              for (int i = 0; i < 2_000; i++) {
                out.write(line);
                out.flush();
                Thread.sleep(20);
              }
            } else {
              for (String line : chatLines) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (chatLineDelayMillis > 0) {
                  Thread.sleep(chatLineDelayMillis);
                }
              }
            }
          } catch (IOException dropped) {
            chatDropped.countDown();
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  /** The NDJSON lines {@code /api/chat} streams, e.g. from {@link #chunk} and {@link #doneChunk}. */
  public FakeOllama chatLines(String... lines) {
    chatLines = List.of(lines);
    chatForever = false;
    return this;
  }

  /** Streams one-character tokens every 20 ms until the API side drops the connection. */
  public FakeOllama chatForever() {
    chatForever = true;
    chatDropped = new CountDownLatch(1);
    return this;
  }

  public FakeOllama chatStatus(int status) {
    chatStatus = status;
    return this;
  }

  public FakeOllama chatLineDelayMillis(long millis) {
    chatLineDelayMillis = millis;
    return this;
  }

  /** Makes streaming {@code /api/chat} wait (before any byte) until {@link #releaseChat()}. */
  public FakeOllama holdChat() {
    chatGate = new CountDownLatch(1);
    return this;
  }

  public void releaseChat() {
    chatGate.countDown();
  }

  /** The reply to non-streaming chats (titles, summaries). */
  public FakeOllama completionReply(String reply) {
    completionReply = reply;
    return this;
  }

  public FakeOllama completionStatus(int status) {
    completionStatus = status;
    return this;
  }

  public List<String> chatBodies() {
    return chatBodies;
  }

  public static String chunk(String content) {
    return "{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},\"done\":false}";
  }

  public static String thinkingChunk(String thinking) {
    return "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\""
        + thinking
        + "\"},\"done\":false}";
  }

  public static String doneChunk(int promptTokens, int evalTokens) {
    return "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\","
        + "\"prompt_eval_count\":"
        + promptTokens
        + ",\"eval_count\":"
        + evalTokens
        + ",\"eval_duration\":1000000000,\"load_duration\":0}";
  }

  public String address() {
    return "127.0.0.1:" + server.getAddress().getPort();
  }

  public FakeOllama tags(String... models) {
    tagsJson = "{\"models\":[" + String.join(",", models) + "]}";
    return this;
  }

  public FakeOllama ps(String... models) {
    psJson = "{\"models\":[" + String.join(",", models) + "]}";
    return this;
  }

  /** {@code /api/show} for the model: native context and capabilities (none = old Ollama). */
  public FakeOllama show(String model, int nativeContext, String... capabilities) {
    String caps =
        capabilities.length == 0
            ? ""
            : ",\"capabilities\":[\"" + String.join("\",\"", capabilities) + "\"]";
    showByModel.put(
        model, "{\"model_info\":{\"llama.context_length\":" + nativeContext + "}" + caps + "}");
    return this;
  }

  /** Makes {@code /api/generate} block until {@link #releaseGenerate()}. */
  public FakeOllama holdGenerate() {
    generateGate = new CountDownLatch(1);
    return this;
  }

  public void releaseGenerate() {
    generateGate.countDown();
  }

  public FakeOllama generateStatus(int status) {
    generateStatus = status;
    return this;
  }

  public List<String> generateBodies() {
    return generateBodies;
  }

  public static String tag(
      String name, String digest, long size, String family, String parameterSize, String quant) {
    return "{\"name\":\""
        + name
        + "\",\"model\":\""
        + name
        + "\",\"size\":"
        + size
        + ",\"digest\":\""
        + digest
        + "\",\"details\":{\"family\":\""
        + family
        + "\",\"parameter_size\":\""
        + parameterSize
        + "\",\"quantization_level\":\""
        + quant
        + "\"}}";
  }

  public static String loaded(String name, long sizeVram, String expiresAt) {
    return "{\"name\":\""
        + name
        + "\",\"model\":\""
        + name
        + "\",\"size_vram\":"
        + sizeVram
        + ",\"expires_at\":\""
        + expiresAt
        + "\"}";
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    generateGate.countDown();
    chatGate.countDown();
    if (closed.compareAndSet(false, true)) {
      server.stop(0);
    }
  }
}
