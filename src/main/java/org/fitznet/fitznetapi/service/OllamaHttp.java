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
        throw new IOException("Node returned HTTP " + status);
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
