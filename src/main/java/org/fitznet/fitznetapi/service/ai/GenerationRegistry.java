package org.fitznet.fitznetapi.service.ai;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The conversations that have a generation in flight on this API instance: one per conversation
 * (a second turn answers 409). A stored {@code streaming} message of a conversation that is not in
 * this set is an orphan (restart, crash) and is shown as {@code error / INTERRUPTED}.
 */
@Component
public class GenerationRegistry {

  private final Set<String> active = ConcurrentHashMap.newKeySet();

  /** True when the caller now owns the conversation's generation slot. */
  public boolean tryAcquire(String conversationId) {
    return active.add(conversationId);
  }

  public void release(String conversationId) {
    active.remove(conversationId);
  }

  public boolean isActive(String conversationId) {
    return active.contains(conversationId);
  }
}
