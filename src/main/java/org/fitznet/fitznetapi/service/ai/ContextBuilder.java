package org.fitznet.fitznetapi.service.ai;

import java.util.ArrayList;
import java.util.List;
import org.fitznet.fitznetapi.model.AiMessage;
import org.fitznet.fitznetapi.model.StoredSummary;

/**
 * Builds the prompt that fits the model's window (contract section 8), newest first:
 *
 * <pre>
 * budget = contextLength - replyReserve - tokens(system prompt)
 * replyReserve = min(max(contextLength / 4, 256), 2048)
 * </pre>
 *
 * <ol>
 *   <li>If the whole history fits the budget, send it all (a stored summary is ignored but kept).
 *   <li>Otherwise, if a summary exists: send it as a system message plus the messages after
 *       {@code coversThroughSeq}, newest first, as many as fit.
 *   <li>Otherwise send the newest messages that fit.
 *   <li>The newest user message is always included; if it alone does not fit the plan is
 *       {@code tooSmall}.
 * </ol>
 *
 * Replayed: all user messages and assistant messages with content and status complete or
 * cancelled. Error and streaming messages are skipped; {@code thinking} is never part of the
 * prompt. Nothing is ever deleted by trimming.
 */
public final class ContextBuilder {

  public static final String SUMMARY_PREFIX = "Summary of the earlier conversation:\n";

  private ContextBuilder() {}

  public static int replyReserve(int contextLength) {
    return Math.min(Math.max(contextLength / 4, 256), 2048);
  }

  /** True for the stored messages that are part of a prompt. */
  public static boolean replayable(AiMessage message) {
    if (AiMessage.ROLE_USER.equals(message.getRole())) {
      return true;
    }
    return AiMessage.ROLE_ASSISTANT.equals(message.getRole())
        && message.getContent() != null
        && !message.getContent().isEmpty()
        && (AiMessage.STATUS_COMPLETE.equals(message.getStatus())
            || AiMessage.STATUS_CANCELLED.equals(message.getStatus()));
  }

  /**
   * @param stored every stored message in {@code seq} order (non-replayable ones are skipped)
   * @param summary the conversation's stored summary, or null
   * @param systemPrompt nullable
   * @param modelHasVision images of older messages are only sent to a vision model
   */
  public static PromptPlan build(
      List<AiMessage> stored,
      StoredSummary summary,
      String systemPrompt,
      int contextLength,
      boolean modelHasVision) {
    List<AiMessage> history = stored.stream().filter(ContextBuilder::replayable).toList();
    int systemTokens =
        systemPrompt == null || systemPrompt.isBlank() ? 0 : TokenEstimator.tokens(systemPrompt);
    int budget = contextLength - replyReserve(contextLength) - systemTokens;

    int newestUser = -1;
    for (int i = history.size() - 1; i >= 0; i--) {
      if (AiMessage.ROLE_USER.equals(history.get(i).getRole())) {
        newestUser = i;
        break;
      }
    }
    int[] cost = new int[history.size()];
    int total = 0;
    for (int i = 0; i < history.size(); i++) {
      cost[i] = cost(history.get(i), modelHasVision || i == newestUser);
      total += cost[i];
    }

    if (total <= budget) {
      return plan(history, 0, history.size(), null, systemTokens, cost, total, newestUser, modelHasVision);
    }
    boolean tooSmall = newestUser >= 0 && cost[newestUser] > budget;

    if (summary != null && newestUser >= 0) {
      int summaryTokens = TokenEstimator.tokens(SUMMARY_PREFIX + summary.text());
      int start = suffixStart(history, cost, budget - summaryTokens, newestUser, summary.coversThroughSeq());
      if (start >= 0) {
        return plan(
            history, start, history.size(), summary, systemTokens + summaryTokens, cost, total,
            newestUser, modelHasVision);
      }
    }
    int start = newestUser < 0 ? history.size() : suffixStart(history, cost, budget, newestUser, -1);
    if (start < 0) {
      start = newestUser; // too small: still send the newest user message, flagged
    }
    PromptPlan plan =
        plan(history, start, history.size(), null, systemTokens, cost, total, newestUser, modelHasVision);
    return tooSmall
        ? new PromptPlan(
            plan.messages(),
            null,
            plan.promptTokensEstimate(),
            plan.totalTokens(),
            plan.trimmedCount(),
            plan.trimmed(),
            plan.imagesOmitted(),
            true)
        : plan;
  }

  /**
   * First index of the newest contiguous suffix that fits {@code remaining} and contains the
   * newest user message, ignoring messages with {@code seq <= coveredThroughSeq}; -1 when even the
   * newest user message does not fit.
   */
  private static int suffixStart(
      List<AiMessage> history, int[] cost, int remaining, int newestUser, int coveredThroughSeq) {
    int start = history.size();
    for (int i = history.size() - 1; i >= 0; i--) {
      if (history.get(i).getSeq() <= coveredThroughSeq || cost[i] > remaining) {
        break;
      }
      remaining -= cost[i];
      start = i;
    }
    return start <= newestUser ? start : -1;
  }

  private static PromptPlan plan(
      List<AiMessage> history,
      int from,
      int to,
      StoredSummary summaryUsed,
      int fixedTokens,
      int[] cost,
      int total,
      int newestUser,
      boolean modelHasVision) {
    List<AiMessage> included = new ArrayList<>(history.subList(from, to));
    int used = fixedTokens;
    int imagesOmitted = 0;
    for (int i = from; i < to; i++) {
      used += cost[i];
      if (!modelHasVision && i != newestUser) {
        imagesOmitted += imageCount(history.get(i));
      }
    }
    List<AiMessage> trimmed = new ArrayList<>();
    for (int i = 0; i < from; i++) {
      boolean covered = summaryUsed != null && history.get(i).getSeq() <= summaryUsed.coversThroughSeq();
      if (!covered) {
        trimmed.add(history.get(i));
      }
    }
    return new PromptPlan(
        included, summaryUsed, used, total, trimmed.size(), trimmed, imagesOmitted, false);
  }

  private static int cost(AiMessage message, boolean imagesSent) {
    return TokenEstimator.message(message.getContent(), imagesSent ? imageCount(message) : 0);
  }

  private static int imageCount(AiMessage message) {
    return message.getAttachments() == null
        ? 0
        : (int)
            message.getAttachments().stream().filter(a -> "image".equals(a.kind())).count();
  }
}
