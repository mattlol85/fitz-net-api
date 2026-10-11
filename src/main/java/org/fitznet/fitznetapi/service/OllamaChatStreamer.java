package org.fitznet.fitznetapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Streams a chat from a node's Ollama {@code /api/chat} to the caller as Server-Sent Events.
 *
 * <p>Streaming keeps bytes flowing through nginx and Cloudflare (which cut buffered requests off
 * at 60s / 100s) for as long as the model is producing tokens. Events:
 *
 * <ul>
 *   <li>{@code meta} - {@code {"model","nodeId"}}, sent immediately
 *   <li>{@code token} - {@code {"content"}}, one per reply chunk
 *   <li>{@code done} - {@code {}}, generation finished
 *   <li>{@code error} - {@code {"message"}}, terminal failure
 * </ul>
 *
 * <p>Chunks with no reply content (e.g. a thinking model's reasoning) are sent as SSE comments so
 * proxies still see traffic. Uses {@link HttpURLConnection} directly rather than the shared
 * RestClient because Spring's response close drains the body to completion, which would keep the
 * GPU busy; {@link HttpURLConnection#disconnect()} drops the socket and Ollama cancels the
 * generation. Prompt and reply content are never logged.
 */
@Component
public class OllamaChatStreamer {

  private static final Logger logger = LoggerFactory.getLogger(OllamaChatStreamer.class);

  private final ObjectMapper objectMapper;
  private final Duration connectTimeout;
  private final Duration readTimeout;
  private final Duration maxStreamDuration;

  public OllamaChatStreamer(
      ObjectMapper objectMapper,
      @Value("${ollama.connect-timeout:5s}") Duration connectTimeout,
      @Value("${ollama.read-timeout:180s}") Duration readTimeout,
      @Value("${ollama.max-stream-duration:10m}") Duration maxStreamDuration) {
    this.objectMapper = objectMapper;
    this.connectTimeout = connectTimeout;
    this.readTimeout = readTimeout;
    this.maxStreamDuration = maxStreamDuration;
  }

  public void stream(ChatTarget target, String prompt, OutputStream out) {
    SseWriter sse = new SseWriter(out);
    long deadline = System.nanoTime() + maxStreamDuration.toNanos();
    HttpURLConnection connection = null;
    try {
      sse.event("meta", Map.of("model", target.model(), "nodeId", target.nodeId()));
      connection = openChat(target, prompt);
      int status = connection.getResponseCode();
      if (status / 100 != 2) {
        logger.warn("Node {} returned HTTP {} for chat", target.nodeId(), status);
        sse.event("error", Map.of("message", "Node returned HTTP " + status));
        return;
      }
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (System.nanoTime() > deadline) {
            logger.warn(
                "Chat on node {} exceeded max duration {}, cancelling",
                target.nodeId(),
                maxStreamDuration);
            sse.event(
                "error",
                Map.of(
                    "message",
                    "Generation exceeded the " + maxStreamDuration.toSeconds() + "s limit"));
            return;
          }
          if (line.isBlank()) {
            continue;
          }
          JsonNode chunk = objectMapper.readTree(line);
          if (chunk.hasNonNull("error")) {
            sse.event("error", Map.of("message", "Node error: " + chunk.get("error").asText()));
            return;
          }
          String content = chunk.path("message").path("content").asText("");
          if (content.isEmpty()) {
            sse.comment();
          } else {
            sse.event("token", Map.of("content", content));
          }
          if (chunk.path("done").asBoolean(false)) {
            sse.event("done", Map.of());
            return;
          }
        }
        sse.event("error", Map.of("message", "Node closed the stream before finishing"));
      }
    } catch (UncheckedIOException clientGone) {
      logger.info("Client disconnected from chat on node {}, cancelling", target.nodeId());
    } catch (SocketTimeoutException ex) {
      logger.warn("Node {} timed out: {}", target.nodeId(), ex.getMessage());
      sendErrorQuietly(
          sse, "Node stopped responding for " + readTimeout.toSeconds() + "s");
    } catch (IOException ex) {
      logger.warn(
          "Failed to reach node {} at {}: {}", target.nodeId(), target.address(), ex.getMessage());
      sendErrorQuietly(sse, "Could not reach node: " + ex.getMessage());
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private HttpURLConnection openChat(ChatTarget target, String prompt) throws IOException {
    Map<String, Object> request =
        Map.of(
            "model", target.model(),
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "stream", true);
    HttpURLConnection connection =
        (HttpURLConnection)
            URI.create("http://" + target.address() + "/api/chat").toURL().openConnection();
    connection.setConnectTimeout((int) connectTimeout.toMillis());
    connection.setReadTimeout((int) readTimeout.toMillis());
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setDoOutput(true);
    try (OutputStream body = connection.getOutputStream()) {
      objectMapper.writeValue(body, request);
    }
    return connection;
  }

  private void sendErrorQuietly(SseWriter sse, String message) {
    try {
      sse.event("error", Map.of("message", message));
    } catch (UncheckedIOException clientGone) {
      // Nobody left to tell.
    }
  }

  /** Writes SSE frames, surfacing downstream write failures as {@link UncheckedIOException}. */
  private final class SseWriter {
    private final OutputStream out;

    SseWriter(OutputStream out) {
      this.out = out;
    }

    void event(String name, Object data) {
      try {
        write("event: " + name + "\ndata: " + objectMapper.writeValueAsString(data) + "\n\n");
      } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
        throw new IllegalStateException(ex);
      }
    }

    void comment() {
      write(":\n\n");
    }

    private void write(String frame) {
      try {
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }
  }
}
