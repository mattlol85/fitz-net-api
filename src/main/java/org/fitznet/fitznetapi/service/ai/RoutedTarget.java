package org.fitznet.fitznetapi.service.ai;

import java.util.List;
import org.fitznet.fitznetapi.service.ChatTarget;

/**
 * A target resolved to a concrete node: where to send the turn and what is known about the model
 * there.
 *
 * @param address the node's Ollama host:port; internal, never sent to a client
 * @param auto routed by Auto rather than an explicit node
 * @param capabilities {@code completion}, {@code vision}, {@code thinking}, {@code tools}; empty
 *     when unknown
 * @param contextLength effective window on this node, {@code min(native, ollama.max-num-ctx)}
 * @param loaded the model is in memory on the node, or a warm-up for it is in flight
 */
public record RoutedTarget(
    String nodeId,
    String nodeName,
    String address,
    String model,
    boolean auto,
    List<String> capabilities,
    int contextLength,
    boolean loaded) {

  public boolean hasCapability(String capability) {
    return capabilities.contains(capability);
  }

  /** The same target with the window capped, e.g. at a preset's {@code numCtx}. */
  public RoutedTarget withContextCap(Integer cap) {
    if (cap == null || cap >= contextLength) {
      return this;
    }
    return new RoutedTarget(
        nodeId, nodeName, address, model, auto, capabilities, cap, loaded);
  }

  public ChatTarget toChatTarget() {
    return new ChatTarget(nodeId, address, model);
  }
}
