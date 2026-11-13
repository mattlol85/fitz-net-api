package org.fitznet.fitznetapi.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal one-shot JSON calls to a node's Ollama ({@code http://host:port/api/...}). Uses
 * {@link HttpURLConnection} like the chat streamer so the socket is always dropped on the way out.
 * Non-2xx answers and unparsable bodies are {@link IOException}s; nothing here logs request or
 * response bodies.
 */
public final class OllamaHttp {

  private OllamaHttp() {}

  /** Longest part of an error body that is read. */
  private static final int ERROR_BODY_BYTES = 2048;

  /** Longest error text kept. */
  public static final int ERROR_TEXT_CHARS = 300;

  /**
   * A non-2xx answer. {@link #getMessage()} is only the status; {@link #errorText()} is Ollama's
   * own {@code {"error": "..."}} text when there was one. The text may echo request content, so keep
   * it out of logs above debug level.
   */
  public static final class OllamaHttpException extends IOException {
    private final int status;
    private final String errorText;

    public OllamaHttpException(int status, String errorText) {
      super("Node returned HTTP " + status);
      this.status = status;
      this.errorText = errorText;
    }

    public int status() {
      return status;
    }

    /** Ollama's error text (at most {@link #ERROR_TEXT_CHARS} characters), or null. */
    public String errorText() {
      return errorText;
    }
  }

  /** The {@code error} string of a JSON error body, clipped; null when there is none. */
  public static String errorText(ObjectMapper mapper, byte[] body) {
    if (body == null || body.length == 0) {
      return null;
    }
    try {
      JsonNode json = mapper.readTree(body);
      JsonNode error = json.path("error");
      if (error.isString() && !error.asText().isBlank()) {
        String text = error.asText().strip();
        return text.length() <= ERROR_TEXT_CHARS ? text : text.substring(0, ERROR_TEXT_CHARS);
      }
    } catch (JacksonException ex) {
      // not JSON (or cut off): no text
    }
    return null;
  }

  public static JsonNode get(
      ObjectMapper mapper, String address, String path, Duration connect, Duration read)
      throws IOException {
    return call(mapper, "GET", address, path, null, connect, read);
  }

  public static JsonNode post(
      ObjectMapper mapper,
      String address,
      String path,
      Object body,
      Duration connect,
      Duration read)
      throws IOException {
    return call(mapper, "POST", address, path, body, connect, read);
  }

  private static JsonNode call(
      ObjectMapper mapper,
      String method,
      String address,
      String path,
      Object body,
      Duration connect,
      Duration read)
      throws IOException {
    HttpURLConnection connection = null;
    try {
      connection =
          (HttpURLConnection) URI.create("http://" + address + path).toURL().openConnection();
      connection.setConnectTimeout((int) connect.toMillis());
      connection.setReadTimeout((int) read.toMillis());
      connection.setRequestMethod(method);
      if (body != null) {
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
          mapper.writeValue(out, body);
        }
      }
      int status = connection.getResponseCode();
      if (status / 100 != 2) {
        String text = null;
        try (InputStream errors = connection.getErrorStream()) {
          if (errors != null) {
            text = errorText(mapper, errors.readNBytes(ERROR_BODY_BYTES));
          }
        } catch (IOException ignored) {
          // no body: the status is all we have
        }
        throw new OllamaHttpException(status, text);
      }
      try (InputStream in = connection.getInputStream()) {
        return mapper.readTree(in);
      }
    } catch (JacksonException ex) {
      throw new IOException("Node returned invalid JSON", ex);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }
}
