package org.fitznet.fitznetapi.tracing;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Carries the caller's MDC (notably {@code correlationId}) onto another thread, so catalogue
 * fetches, warm-ups and the keep-alive ticker log under the id of the request that caused them.
 * The target thread's own MDC is restored afterwards.
 */
public final class MdcPropagation {

  private MdcPropagation() {}

  public static Runnable wrap(Runnable task) {
    Map<String, String> context = MDC.getCopyOfContextMap();
    return () -> {
      Map<String, String> previous = install(context);
      try {
        task.run();
      } finally {
        restore(previous);
      }
    };
  }

  public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
    Map<String, String> context = MDC.getCopyOfContextMap();
    return () -> {
      Map<String, String> previous = install(context);
      try {
        return task.get();
      } finally {
        restore(previous);
      }
    };
  }

  public static <T> Callable<T> wrapCallable(Callable<T> task) {
    Map<String, String> context = MDC.getCopyOfContextMap();
    return () -> {
      Map<String, String> previous = install(context);
      try {
        return task.call();
      } finally {
        restore(previous);
      }
    };
  }

  private static Map<String, String> install(Map<String, String> context) {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    if (context == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(context);
    }
    return previous;
  }

  private static void restore(Map<String, String> previous) {
    if (previous == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(previous);
    }
  }
}
