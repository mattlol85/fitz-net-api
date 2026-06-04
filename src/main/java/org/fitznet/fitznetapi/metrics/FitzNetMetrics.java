package org.fitznet.fitznetapi.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class FitzNetMetrics {

  private static final String USER_OPERATION_COUNTER = "fitznet.user.operations";
  private static final String USER_OPERATION_TIMER = "fitznet.user.operation";
  private static final String ENCRYPTION_OPERATION_COUNTER = "fitznet.encryption.operations";
  private static final String ENCRYPTION_OPERATION_TIMER = "fitznet.encryption.operation";
  private static final String API_FAILURE_COUNTER = "fitznet.api.failures";

  private final MeterRegistry meterRegistry;

  public FitzNetMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public Timer.Sample startSample() {
    return Timer.start(meterRegistry);
  }

  public void recordUserOperation(String operation, String result, Timer.Sample sample) {
    incrementCounter(
        USER_OPERATION_COUNTER,
        "Count of user management service operations by outcome",
        "operation",
        operation,
        "result",
        result);
    stopTimer(
        USER_OPERATION_TIMER,
        "Duration of user management service operations by outcome",
        sample,
        "operation",
        operation,
        "result",
        result);
  }

  public void recordEncryptionOperation(String operation, String result, Timer.Sample sample) {
    incrementCounter(
        ENCRYPTION_OPERATION_COUNTER,
        "Count of encryption service operations by outcome",
        "operation",
        operation,
        "result",
        result);
    stopTimer(
        ENCRYPTION_OPERATION_TIMER,
        "Duration of encryption service operations by outcome",
        sample,
        "operation",
        operation,
        "result",
        result);
  }

  public void recordApiFailure(String type, String status) {
    incrementCounter(
        API_FAILURE_COUNTER,
        "Count of API failures by handler type and HTTP status",
        "type",
        type,
        "status",
        status);
  }

  private void incrementCounter(String name, String description, String... tags) {
    Counter.builder(name).description(description).tags(tags).register(meterRegistry).increment();
  }

  private void stopTimer(String name, String description, Timer.Sample sample, String... tags) {
    sample.stop(Timer.builder(name).description(description).tags(tags).register(meterRegistry));
  }
}
