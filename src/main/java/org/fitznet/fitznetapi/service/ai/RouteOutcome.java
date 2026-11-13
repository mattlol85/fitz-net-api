package org.fitznet.fitznetapi.service.ai;

import java.util.List;

/** Result of {@link ModelRouter#route}: a concrete target, or a typed failure. */
public sealed interface RouteOutcome {

  record Routed(RoutedTarget target) implements RouteOutcome {}

  /**
   * @param code one of {@link ChatErrorCodes}
   * @param alternatives other nodes with the same model in Auto order (possibly empty)
   */
  record Failed(String code, String message, boolean retryable, List<Alternative> alternatives)
      implements RouteOutcome {}
}
