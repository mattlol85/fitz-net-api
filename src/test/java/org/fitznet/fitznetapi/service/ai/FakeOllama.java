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
 * {@code /api/show} and {@code /api/generate} with configurable answers and call counters.
 */
public final class FakeOllama implements AutoCloseable {

  private final HttpServer server;
  private final java.util.concurrent.atomic.AtomicBoolean closed =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final Map<String, String> showByModel = new ConcurrentHashMap<>();
  private final List<String> generateBodies = new CopyOnWriteArrayList<>();

  public final AtomicInteger tagsCalls = new AtomicInteger();
  public final AtomicInteger psCalls = new AtomicInteger();
  public final AtomicInteger showCalls = new AtomicInteger();
  public final AtomicInteger generateCalls = new AtomicInteger();

  private volatile String tagsJson = "{\"models\":[]}";
  private volatile String psJson = "{\"models\":[]}";
  private volatile int generateStatus = 200;
  private volatile CountDownLatch generateGate = new CountDownLatch(0);

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
    server.start();
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
    if (closed.compareAndSet(false, true)) {
      server.stop(0);
    }
  }
}
