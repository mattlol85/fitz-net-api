package org.fitznet.fitznetapi.service.ai;

import java.util.List;
import org.fitznet.fitznetapi.model.AiMessage;
import org.fitznet.fitznetapi.model.StoredSummary;

/**
 * What {@link ContextBuilder} decided to send.
 *
 * @param messages the stored messages to send, in {@code seq} order
 * @param summaryUsed the summary sent as a system message, or null
 * @param promptTokensEstimate system prompt + summary + included messages
 * @param totalTokens estimate of the whole replayable stored history
 * @param trimmedCount stored messages neither sent nor covered by the summary in use
 * @param trimmed those messages (what "Summarise older turns" works on)
 * @param imagesOmitted images of older messages dropped because the model has no vision
 * @param tooSmall the newest user message alone does not fit ({@code CONTEXT_TOO_SMALL})
 */
public record PromptPlan(
    List<AiMessage> messages,
    StoredSummary summaryUsed,
    int promptTokensEstimate,
    int totalTokens,
    int trimmedCount,
    List<AiMessage> trimmed,
    int imagesOmitted,
    boolean tooSmall) {

  public boolean summaryActive() {
    return summaryUsed != null;
  }

  public boolean fits() {
    return trimmedCount == 0;
  }
}
