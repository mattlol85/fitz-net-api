package org.fitznet.fitznetapi.service.ai;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * In-memory count of chat generations this API currently has open against each node. Legacy
 * ({@code /node/{id}/chat*}) and v2 ({@code /ai/**}) streams both count, so a node serving a turn
 * shows as BUSY and Auto routing prefers the idler node. Nothing is persisted: a restart resets it,
 * which is right because every open stream dies with the process.
 */
@Component
public class NodeActivityTracker {

  private final ConcurrentHashMap<String, AtomicInteger> active = new ConcurrentHashMap<>();

  /**
   * Marks one generation as started on the node. Close the returned handle in a {@code finally};
   * closing twice is harmless.
   */
  public Generation begin(String nodeId) {
    active.computeIfAbsent(nodeId, id -> new AtomicInteger()).incrementAndGet();
    return new Generation(nodeId);
  }

  /** Number of generations currently open on the node. */
  public int active(String nodeId) {
    AtomicInteger count = active.get(nodeId);
    return count == null ? 0 : Math.max(0, count.get());
  }

  /** One open generation; {@link #close()} releases it exactly once. */
  public final class Generation implements AutoCloseable {

    private final String nodeId;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Generation(String nodeId) {
      this.nodeId = nodeId;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        AtomicInteger count = active.get(nodeId);
        if (count != null) {
          count.decrementAndGet();
        }
      }
    }
  }
}
