package org.fitznet.fitznetapi.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcPropagationTest {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  @AfterEach
  void tearDown() {
    MDC.clear();
    executor.shutdownNow();
  }

  @Test
  void shouldCarryTheCorrelationIdOntoAnotherThread() throws Exception {
    MDC.put(TraceContext.MDC_CORRELATION_ID, "trace-42");

    String seen =
        CompletableFuture.supplyAsync(
                MdcPropagation.wrapSupplier(() -> MDC.get(TraceContext.MDC_CORRELATION_ID)),
                executor)
            .get();

    assertEquals("trace-42", seen);
  }

  @Test
  void shouldNotLeakTheContextIntoLaterTasksOnTheSameThread() throws Exception {
    MDC.put(TraceContext.MDC_CORRELATION_ID, "trace-42");
    CompletableFuture.runAsync(MdcPropagation.wrap(() -> {}), executor).get();
    MDC.clear();

    String seen =
        CompletableFuture.supplyAsync(() -> MDC.get(TraceContext.MDC_CORRELATION_ID), executor)
            .get();

    assertNull(seen);
  }

  @Test
  void shouldRunWithAnEmptyContextWhenTheCallerHadNone() throws Exception {
    executor.submit(() -> MDC.put(TraceContext.MDC_CORRELATION_ID, "stale")).get();
    MDC.clear();

    String seen =
        CompletableFuture.supplyAsync(
                MdcPropagation.wrapSupplier(() -> MDC.get(TraceContext.MDC_CORRELATION_ID)),
                executor)
            .get();

    assertNull(seen);
  }
}
