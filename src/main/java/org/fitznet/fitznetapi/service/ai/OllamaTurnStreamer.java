package org.fitznet.fitznetapi.service.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.fitznet.fitznetapi.service.ChatEventSink;
import org.fitznet.fitznetapi.service.OllamaHttp;
import org.fitznet.fitznetapi.service.ai.TurnResult.Ended;
import org.fitznet.fitznetapi.service.ai.TurnResult.TurnError;
import org.fitznet.fitznetapi.service.ai.TurnResult.TurnStats;
import org.fitznet.fitznetapi.tracing.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The v2 turn engine: streams one generation from a node's Ollama {@code /api/chat} to a
 * {@link ChatEventSink} (contract 7.1 steps 5 to 8 and 7.2) and reports how it ended.
 *
 * <p>It emits {@code status} ({@code loading_model} when the target is not loaded, {@code
 * generating} on the first upstream chunk), {@code thinking} (Ollama's {@code message.thinking})
 * and {@code token} ({@code message.content}) events. It never emits {@code meta}, {@code done} or
 * {@code error}: the caller does that from the returned {@link TurnResult}, which also carries the
 * content so far for persistence and says whether any upstream chunk had arrived (Auto may only
 * fail over before that).
 *
 * <p><b>Cancel.</b> While a turn is open a ticker thread writes a keep-alive to the sink whenever
 * nothing was written for {@code ai.chat.cancel-poll-interval}. A failed write (browser gone) or a
 * cancelled gRPC call (the sink throws {@link UncheckedIOException}) disconnects the upstream
 * connection, which wakes a reader blocked on a silent model and makes Ollama stop generating. The
 * same ticker enforces {@code ollama.max-stream-duration}. Sink writes are serialised because the
 * ticker and the reader share the sink. The MDC {@code correlationId} is carried onto the ticker.
 * Each turn counts as one in-flight generation on its node for as long as it runs.
 *
 * <p>Talks to Ollama through the JDK {@link HttpClient} (HTTP/1.1): cancelling the pending future
 * before the headers, or closing the body stream after, drops the connection even while another
 * thread is blocked reading ({@code HttpURLConnection.disconnect()} cannot). Dropping the socket is
 * what cancels the generation on the GPU. The JDK client has no per-read timeout, so the ticker
 * also enforces {@code ollama.read-timeout} as "no upstream byte for that long". Prompt, reply and
 * reasoning content are never logged.
 */
@Component
public class OllamaTurnStreamer {

  private static final Logger logger = LoggerFactory.getLogger(OllamaTurnStreamer.class);

  private final ObjectMapper objectMapper;
  private final NodeActivityTracker activityTracker;
  private final Duration connectTimeout;
  private final Duration readTimeout;
  private final Duration maxStreamDuration;
  private final String keepAlive;
  private final Duration cancelPollInterval;
  private final HttpClient httpClient;

  public OllamaTurnStreamer(
      ObjectMapper objectMapper,
      NodeActivityTracker activityTracker,
      @Value("${ollama.connect-timeout:5s}") Duration connectTimeout,
      @Value("${ollama.read-timeout:180s}") Duration readTimeout,
      @Value("${ollama.max-stream-duration:10m}") Duration maxStreamDuration,
      @Value("${ollama.keep-alive:30m}") String keepAlive,
      @Value("${ai.chat.cancel-poll-interval:2s}") Duration cancelPollInterval) {
    this.objectMapper = objectMapper;
    this.activityTracker = activityTracker;
    this.connectTimeout = connectTimeout;
    this.readTimeout = readTimeout;
    this.maxStreamDuration = maxStreamDuration;
    this.keepAlive = keepAlive;
    this.cancelPollInterval = cancelPollInterval;
    this.httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout)
            .build();
  }

  /**
   * Runs the turn on the calling thread and returns when it ended. Never throws for a node or
   * client failure; those are in the result.
   */
  public TurnResult stream(TurnRequest request, ChatEventSink sink) {
    try (NodeActivityTracker.Generation ignored =
        activityTracker.begin(request.target().nodeId())) {
      return new Run(request, sink).execute();
    }
  }

  /** State of one running turn. */
  private final class Run {

    private final TurnRequest request;
    private final RoutedTarget target;
    private final ChatEventSink sink;
    private final ReentrantLock sinkLock = new ReentrantLock();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final long startedAtNanos = System.nanoTime();
    private final long receivedAtNanos;

    private volatile CompletableFuture<HttpResponse<InputStream>> pending;
    private volatile InputStream responseBody;
    private volatile boolean silenceTimedOut;
    private volatile long lastUpstreamNanos = startedAtNanos;
    private volatile boolean cancelled;
    private volatile boolean deadlineExceeded;
    private volatile long lastWriteNanos = startedAtNanos;

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private int chunks;
    private long firstOutputNanos;
    private long firstThinkingNanos;
    private long firstTokenNanos;

    private Ended ended;
    private TurnError error;
    private String finishReason;
    private Integer promptTokens;
    private Integer completionTokens;
    private long loadNanos;
    private long evalNanos;

    Run(TurnRequest request, ChatEventSink sink) {
      this.request = request;
      this.target = request.target();
      this.sink = sink;
      this.receivedAtNanos =
          request.receivedAtNanos() != 0 ? request.receivedAtNanos() : startedAtNanos;
    }

    TurnResult execute() {
      Thread ticker = new Thread(MdcPropagation.wrap(this::tick), "ai-turn-keepalive");
      ticker.setDaemon(true);
      ticker.start();
      try {
        run();
      } catch (UncheckedIOException clientGone) {
        cancelled = true;
      } catch (IOException ex) {
        classify(ex);
      } catch (JacksonException ex) {
        fail(ChatErrorCodes.NODE_ERROR, "Node sent an unreadable response");
      } catch (RuntimeException ex) {
        logger.error("Turn on node {} failed unexpectedly", target.nodeId(), ex);
        fail(ChatErrorCodes.INTERNAL, "Unexpected error while generating");
      } finally {
        finished.countDown();
        disconnect();
        joinQuietly(ticker);
      }
      return result();
    }

    private void run() throws IOException {
      if (!target.loaded()) {
        emit(
            "status",
            Map.of(
                "phase",
                "loading_model",
                "nodeId",
                target.nodeId(),
                "model",
                target.model(),
                "message",
                "Loading " + target.model() + " on " + target.nodeName() + "…"));
      }
      HttpResponse<InputStream> response = open();
      int status = response.statusCode();
      if (status / 100 != 2) {
        logger.warn("Node {} returned HTTP {} for chat", target.nodeId(), status);
        fail(ChatErrorCodes.NODE_ERROR, errorMessage(response, status));
        return;
      }
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          lastUpstreamNanos = System.nanoTime();
          if (System.nanoTime() - startedAtNanos > maxStreamDuration.toNanos()) {
            deadlineExceeded = true;
            fail(
                ChatErrorCodes.TIMEOUT,
                "Generation exceeded the " + maxStreamDuration.toSeconds() + "s limit");
            return;
          }
          if (line.isBlank()) {
            continue;
          }
          JsonNode chunk = objectMapper.readTree(line);
          if (chunk.hasNonNull("error")) {
            fail(ChatErrorCodes.NODE_ERROR, "Node error: " + chunk.get("error").asText());
            return;
          }
          onChunk(chunk);
          if (chunk.path("done").asBoolean(false)) {
            complete(chunk);
            return;
          }
        }
        fail(ChatErrorCodes.NODE_UNREACHABLE, "Node closed the stream before finishing");
      }
    }

    /**
     * Ollama's own error text when the body is JSON with an {@code error} string, else the bare
     * status. The text is only logged at debug level: it can echo what was asked.
     */
    private String errorMessage(HttpResponse<InputStream> response, int status) {
      try {
        byte[] body = response.body().readNBytes(2048);
        String text = OllamaHttp.errorText(objectMapper, body);
        if (text != null) {
          logger.debug("Node {} error text: {}", target.nodeId(), text);
          return "Node error: " + text;
        }
      } catch (IOException | RuntimeException ex) {
        // fall through to the status
      }
      return "Node returned HTTP " + status;
    }

    private HttpResponse<InputStream> open() throws IOException {
      Map<String, Object> options = new LinkedHashMap<>();
      options.put("num_ctx", request.numCtx());
      if (request.temperature() != null) {
        options.put("temperature", request.temperature());
      }
      List<Map<String, Object>> messages = new ArrayList<>();
      if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
        messages.add(Map.of("role", "system", "content", request.systemPrompt()));
      }
      for (TurnMessage message : request.messages()) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", message.role());
        entry.put("content", message.content());
        if (!message.imagesBase64().isEmpty()) {
          entry.put("images", message.imagesBase64());
        }
        messages.add(entry);
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("model", target.model());
      body.put("messages", messages);
      body.put("stream", true);
      body.put("keep_alive", keepAlive);
      body.put("options", options);
      if (request.think()) {
        body.put("think", true);
      }

      byte[] payload = objectMapper.writeValueAsBytes(body);
      HttpRequest httpRequest =
          HttpRequest.newBuilder(URI.create("http://" + target.address() + "/api/chat"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
              .build();
      // Published before sending so the ticker can cancel it at any point.
      CompletableFuture<HttpResponse<InputStream>> future =
          httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
      pending = future;
      if (cancelled || deadlineExceeded || silenceTimedOut) {
        disconnect(); // the ticker fired before we got here
      }
      HttpResponse<InputStream> response;
      try {
        response = future.get();
      } catch (ExecutionException ex) {
        Throwable cause = ex.getCause();
        throw cause instanceof IOException io ? io : new IOException(cause);
      } catch (CancellationException ex) {
        throw new IOException("Request cancelled", ex);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted", ex);
      }
      responseBody = response.body();
      lastUpstreamNanos = System.nanoTime();
      if (cancelled || deadlineExceeded || silenceTimedOut) {
        disconnect();
      }
      return response;
    }

    private void onChunk(JsonNode chunk) {
      if (++chunks == 1) {
        emit("status", Map.of("phase", "generating"));
      }
      JsonNode message = chunk.path("message");
      String thought = message.path("thinking").asText("");
      if (!thought.isEmpty()) {
        thinking.append(thought);
        emit("thinking", Map.of("content", thought));
        long now = System.nanoTime();
        if (firstThinkingNanos == 0) {
          firstThinkingNanos = now;
        }
        if (firstOutputNanos == 0) {
          firstOutputNanos = now;
        }
      }
      String text = message.path("content").asText("");
      if (!text.isEmpty()) {
        content.append(text);
        emit("token", Map.of("content", text));
        long now = System.nanoTime();
        if (firstTokenNanos == 0) {
          firstTokenNanos = now;
        }
        if (firstOutputNanos == 0) {
          firstOutputNanos = now;
        }
      }
    }

    private void complete(JsonNode last) {
      promptTokens = last.hasNonNull("prompt_eval_count") ? last.get("prompt_eval_count").asInt() : null;
      completionTokens = last.hasNonNull("eval_count") ? last.get("eval_count").asInt() : null;
      evalNanos = last.path("eval_duration").asLong(0);
      loadNanos = last.path("load_duration").asLong(0);
      finishReason = "length".equals(last.path("done_reason").asText("")) ? "length" : "stop";
      ended = Ended.COMPLETED;
    }

    private void fail(String code, String message) {
      ended = Ended.ERROR;
      error = new TurnError(code, message);
    }

    private void classify(IOException ex) {
      if (cancelled) {
        return; // we dropped the connection ourselves; the exception is just the wake-up
      }
      if (deadlineExceeded) {
        fail(
            ChatErrorCodes.TIMEOUT,
            "Generation exceeded the " + maxStreamDuration.toSeconds() + "s limit");
      } else if (silenceTimedOut
          || (ex instanceof HttpTimeoutException && !(ex instanceof HttpConnectTimeoutException))) {
        logger.warn("Node {} timed out", target.nodeId());
        fail(
            ChatErrorCodes.TIMEOUT,
            "Node stopped responding for " + readTimeout.toSeconds() + "s");
      } else {
        logger.warn("Failed to reach node {}: {}", target.nodeId(), ex.toString());
        fail(ChatErrorCodes.NODE_UNREACHABLE, "Could not reach " + target.nodeName());
      }
    }

    private TurnResult result() {
      Ended outcome;
      String reason;
      if (ended == Ended.COMPLETED) {
        // The final chunk arrived: complete even if the client vanished a moment later.
        outcome = Ended.COMPLETED;
        reason = finishReason;
      } else if (cancelled) {
        outcome = Ended.CANCELLED;
        reason = "cancelled";
      } else {
        outcome = Ended.ERROR;
        reason = "error";
        if (error == null) {
          error = new TurnError(ChatErrorCodes.INTERNAL, "Unexpected error while generating");
        }
      }
      long now = System.nanoTime();
      Double tokensPerSecond = null;
      if (completionTokens != null && completionTokens > 0 && evalNanos > 0) {
        tokensPerSecond = Math.round(completionTokens / (evalNanos / 1e9) * 10.0) / 10.0;
      }
      TurnStats stats =
          new TurnStats(
              firstOutputNanos == 0 ? null : millis(firstOutputNanos - receivedAtNanos),
              millis(now - receivedAtNanos),
              loadNanos / 1_000_000,
              firstThinkingNanos != 0 && firstTokenNanos != 0
                  ? millis(firstTokenNanos - firstThinkingNanos)
                  : null,
              tokensPerSecond);
      logger.info(
          "Turn on node {} ({}) {} after {}ms, {} chunks, prompt {} completion {}",
          target.nodeId(),
          target.model(),
          outcome,
          stats.totalMs(),
          chunks,
          promptTokens,
          completionTokens);
      return new TurnResult(
          outcome,
          content.toString(),
          thinking.isEmpty() ? null : thinking.toString(),
          outcome == Ended.COMPLETED ? promptTokens : null,
          outcome == Ended.COMPLETED ? completionTokens : null,
          stats,
          reason,
          outcome == Ended.ERROR ? error : null,
          chunks > 0);
    }

    // ---- sink and ticker -------------------------------------------------------------------

    /** Serialised write to the sink; a failed write marks the client as gone and rethrows. */
    private void emit(String name, Map<String, ?> data) {
      sinkLock.lock();
      try {
        sink.event(name, data);
        lastWriteNanos = System.nanoTime();
      } catch (UncheckedIOException clientGone) {
        cancelled = true;
        throw clientGone;
      } finally {
        sinkLock.unlock();
      }
    }

    private void tick() {
      long pollNanos = Math.max(1, cancelPollInterval.toNanos());
      long maxNanos = maxStreamDuration.toNanos();
      long silenceNanos = readTimeout.toNanos();
      try {
        while (finished.getCount() > 0) {
          long now = System.nanoTime();
          long untilDeadline = maxNanos - (now - startedAtNanos);
          if (untilDeadline <= 0) {
            deadlineExceeded = true;
            logger.warn("Turn on node {} exceeded max duration {}, cancelling", target.nodeId(), maxStreamDuration);
            disconnect();
            return;
          }
          long untilSilence = silenceNanos - (now - lastUpstreamNanos);
          if (untilSilence <= 0) {
            silenceTimedOut = true;
            disconnect();
            return;
          }
          long untilPoll = pollNanos - (now - lastWriteNanos);
          if (untilPoll > 0) {
            long wait = Math.min(untilPoll, Math.min(untilDeadline, untilSilence));
            if (finished.await(wait, TimeUnit.NANOSECONDS)) {
              return;
            }
            continue;
          }
          if (!keepAliveOrDisconnect()) {
            return;
          }
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }

    /** Writes a keep-alive frame; returns false once the client is gone and the upstream dropped. */
    private boolean keepAliveOrDisconnect() {
      sinkLock.lock();
      try {
        if (finished.getCount() == 0) {
          return false;
        }
        sink.keepAlive();
        lastWriteNanos = System.nanoTime();
        return true;
      } catch (UncheckedIOException clientGone) {
        cancelled = true;
        logger.info("Client disconnected from turn on node {}, cancelling", target.nodeId());
        disconnect();
        return false;
      } finally {
        sinkLock.unlock();
      }
    }

    private void disconnect() {
      CompletableFuture<HttpResponse<InputStream>> future = pending;
      if (future != null) {
        future.cancel(true);
      }
      InputStream body = responseBody;
      if (body != null) {
        try {
          body.close();
        } catch (IOException ignored) {
          // already gone
        }
      }
    }

    private void joinQuietly(Thread ticker) {
      try {
        ticker.join(5_000);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }

    private long millis(long nanos) {
      return Math.max(0, nanos) / 1_000_000;
    }
  }
}
