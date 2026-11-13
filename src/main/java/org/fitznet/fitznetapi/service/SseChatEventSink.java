package org.fitznet.fitznetapi.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes {@code event: X\ndata: {json}\n\n} frames (and {@code :\n\n} keep-alive comments) to an
 * output stream, flushing each one. Downstream write failures surface as
 * {@link UncheckedIOException}, which is how a closed browser tab is noticed.
 *
 * <p>Not thread-safe: callers that write from more than one thread must serialise.
 */
public final class SseChatEventSink implements ChatEventSink {

  private final OutputStream out;
  private final ObjectMapper objectMapper;

  public SseChatEventSink(OutputStream out, ObjectMapper objectMapper) {
    this.out = out;
    this.objectMapper = objectMapper;
  }

  @Override
  public void event(String name, Map<String, ?> data) {
    try {
      write("event: " + name + "\ndata: " + objectMapper.writeValueAsString(data) + "\n\n");
    } catch (JacksonException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** An SSE comment, so proxies still see traffic. */
  @Override
  public void keepAlive() {
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
