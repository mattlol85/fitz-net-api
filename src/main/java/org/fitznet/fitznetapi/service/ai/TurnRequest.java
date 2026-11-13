package org.fitznet.fitznetapi.service.ai;

import java.util.List;

/**
 * Everything the engine needs to run one generation.
 *
 * @param target an already routed target (node, model, whether it is loaded)
 * @param messages the prompt in order, oldest first; the engine never reorders or trims it
 * @param systemPrompt optional; sent as the first message with role {@code system}
 * @param numCtx sent as {@code options.num_ctx}
 * @param temperature optional {@code options.temperature}
 * @param think send {@code think: true}; only for models that report the {@code thinking}
 *     capability, since Ollama rejects it for others
 * @param receivedAtNanos {@link System#nanoTime()} when the HTTP/gRPC request arrived; the stats
 *     ({@code ttftMs}, {@code totalMs}) are measured from it. {@code 0} means "now".
 */
public record TurnRequest(
    RoutedTarget target,
    List<TurnMessage> messages,
    String systemPrompt,
    int numCtx,
    Double temperature,
    boolean think,
    long receivedAtNanos) {

  public TurnRequest {
    messages = List.copyOf(messages);
  }
}
