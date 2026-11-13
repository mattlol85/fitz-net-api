package org.fitznet.fitznetapi.service.ai;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The warm-ups currently in flight, one per node + model. Kept apart from {@link WarmUpService} so
 * the catalogue and router can read it ("an in-flight warm-up counts as loaded") without a
 * dependency cycle.
 */
@Component
public class WarmUpRegistry {

  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

  /** True when the caller now owns the warm-up; false when one is already in flight. */
  public boolean tryBegin(String nodeId, String model) {
    return inFlight.add(key(nodeId, model));
  }

  public void end(String nodeId, String model) {
    inFlight.remove(key(nodeId, model));
  }

  public boolean isInFlight(String nodeId, String model) {
    return inFlight.contains(key(nodeId, model));
  }

  private static String key(String nodeId, String model) {
    return nodeId + "|" + model;
  }
}
