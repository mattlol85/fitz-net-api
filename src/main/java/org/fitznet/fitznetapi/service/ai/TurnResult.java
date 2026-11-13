package org.fitznet.fitznetapi.service.ai;

/**
 * What a generation produced and how it ended, so the caller can persist the assistant message,
 * decide on Auto failover and write the terminal {@code done} or {@code error} event itself (the
 * engine writes neither, nor {@code meta}).
 *
 * @param content the answer so far
 * @param thinking the reasoning so far; null when there was none
 * @param promptTokens Ollama's {@code prompt_eval_count}; null unless the final chunk arrived
 * @param completionTokens Ollama's {@code eval_count}; null unless the final chunk arrived
 * @param stats timings; {@code tokensPerSecond} is null unless the final chunk arrived
 * @param finishReason {@code stop} or {@code length} when completed, else {@code cancelled} or
 *     {@code error}
 * @param error set when {@code ended == ERROR}
 * @param upstreamStarted at least one upstream chunk arrived (an error line does not count). When
 *     false nothing was written to the client but {@code status}, so Auto may fail over
 *     transparently.
 */
public record TurnResult(
    Ended ended,
    String content,
    String thinking,
    Integer promptTokens,
    Integer completionTokens,
    TurnStats stats,
    String finishReason,
    TurnError error,
    boolean upstreamStarted) {

  public enum Ended {
    /** The final chunk arrived. */
    COMPLETED,
    /** The client went away (failed write, gRPC cancellation); the upstream was dropped. */
    CANCELLED,
    /** A typed failure, see {@link #error}. */
    ERROR
  }

  /** @param code one of {@link ChatErrorCodes} */
  public record TurnError(String code, String message) {}

  /**
   * @param ttftMs request received to the first {@code thinking} or {@code token} written
   * @param totalMs request received to the end of the generation
   * @param loadMs Ollama's {@code load_duration}
   * @param thinkingMs first {@code thinking} to first {@code token}; null without thinking
   * @param tokensPerSecond {@code eval_count / eval_duration}, one decimal; null if unavailable
   */
  public record TurnStats(
      Long ttftMs, long totalMs, long loadMs, Long thinkingMs, Double tokensPerSecond) {}

  public boolean completed() {
    return ended == Ended.COMPLETED;
  }
}
