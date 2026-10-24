package org.fitznet.fitznetapi.service;

import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Where {@link OllamaChatStreamer} sends a streamed chat's events: SSE for REST, a gRPC stream for
 * {@code NodeService/ChatStream}. Both methods throw {@link UncheckedIOException} once the client is
 * gone, which cancels the upstream generation.
 */
public interface ChatEventSink {

  /** A {@code meta}, {@code token}, {@code done} or {@code error} event; see {@link OllamaChatStreamer}. */
  void event(String name, Map<String, ?> data);

  /** A chunk arrived with no reply content (e.g. a thinking model's reasoning). */
  void keepAlive();
}
