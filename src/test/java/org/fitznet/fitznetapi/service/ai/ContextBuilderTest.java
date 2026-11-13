package org.fitznet.fitznetapi.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.fitznet.fitznetapi.model.AiMessage;
import org.fitznet.fitznetapi.model.MessageAttachment;
import org.fitznet.fitznetapi.model.StoredSummary;
import org.junit.jupiter.api.Test;

class ContextBuilderTest {

  /** 300 characters: 100 + 4 = 104 estimated tokens. */
  private static final String BLOCK = "a".repeat(300);

  private static AiMessage message(int seq, String role, String content, String status) {
    return AiMessage.builder()
        .id("m" + seq)
        .seq(seq)
        .role(role)
        .content(content)
        .status(status)
        .attachments(new ArrayList<>())
        .build();
  }

  private static AiMessage user(int seq, String content) {
    return message(seq, AiMessage.ROLE_USER, content, AiMessage.STATUS_COMPLETE);
  }

  private static AiMessage assistant(int seq, String content) {
    return message(seq, AiMessage.ROLE_ASSISTANT, content, AiMessage.STATUS_COMPLETE);
  }

  /** u1 a2 u3 a4 ... alternating, {@code count} messages of 104 tokens each, ending on a user. */
  private static List<AiMessage> history(int count) {
    List<AiMessage> all = new ArrayList<>();
    for (int seq = 1; seq <= count; seq++) {
      all.add(seq % 2 == 1 ? user(seq, BLOCK) : assistant(seq, BLOCK));
    }
    return all;
  }

  private static StoredSummary summary(int coversThroughSeq, String text) {
    return new StoredSummary(text, coversThroughSeq, 10, "m", "n", false, Instant.now());
  }

  private static List<Integer> seqs(PromptPlan plan) {
    return plan.messages().stream().map(AiMessage::getSeq).toList();
  }

  // ---- estimator ---------------------------------------------------------------------------

  @Test
  void shouldEstimateCeilOfCodePointsOverThreePlusFour() {
    assertEquals(4, TokenEstimator.tokens(""));
    assertEquals(5, TokenEstimator.tokens("a"));
    assertEquals(5, TokenEstimator.tokens("abc"));
    assertEquals(6, TokenEstimator.tokens("abcd"));
    assertEquals(104, TokenEstimator.tokens(BLOCK));
  }

  @Test
  void shouldCountCodePointsNotUtf16Units() {
    // Three emoji are six UTF-16 units but three code points.
    assertEquals(5, TokenEstimator.tokens("😀😀😀"));
  }

  @Test
  void shouldAdd768TokensPerImage() {
    assertEquals(5 + 2 * 768, TokenEstimator.message("abc", 2));
  }

  @Test
  void shouldReserveAQuarterOfTheWindowClampedBetween256And2048() {
    assertEquals(256, ContextBuilder.replyReserve(512));
    assertEquals(256, ContextBuilder.replyReserve(1000));
    assertEquals(1024, ContextBuilder.replyReserve(4096));
    assertEquals(2048, ContextBuilder.replyReserve(8192));
    assertEquals(2048, ContextBuilder.replyReserve(131072));
  }

  // ---- the four rules ----------------------------------------------------------------------

  @Test
  void shouldSendTheWholeHistoryWhenItFitsAndIgnoreTheSummary() {
    List<AiMessage> stored = history(5); // 520 tokens, budget 768

    PromptPlan plan = ContextBuilder.build(stored, summary(2, "old"), null, 1024, false);

    assertEquals(List.of(1, 2, 3, 4, 5), seqs(plan));
    assertEquals(0, plan.trimmedCount());
    assertFalse(plan.summaryActive());
    assertNull(plan.summaryUsed());
    assertEquals(520, plan.promptTokensEstimate());
    assertEquals(520, plan.totalTokens());
    assertTrue(plan.fits());
    assertFalse(plan.tooSmall());
  }

  @Test
  void shouldSendTheNewestMessagesThatFitAndReportTheTrimmedCount() {
    List<AiMessage> stored = history(11); // 1144 tokens, budget 768 -> 7 messages fit (728)

    PromptPlan plan = ContextBuilder.build(stored, null, null, 1024, false);

    assertEquals(List.of(5, 6, 7, 8, 9, 10, 11), seqs(plan));
    assertEquals(4, plan.trimmedCount());
    assertEquals(List.of(1, 2, 3, 4), plan.trimmed().stream().map(AiMessage::getSeq).toList());
    assertEquals(728, plan.promptTokensEstimate());
    assertEquals(1144, plan.totalTokens());
    assertFalse(plan.fits());
    assertFalse(plan.summaryActive());
  }

  @Test
  void shouldTrimMoreForASmallerWindow() {
    List<AiMessage> stored = history(11);

    PromptPlan roomy = ContextBuilder.build(stored, null, null, 2048, false); // budget 1536
    PromptPlan tight = ContextBuilder.build(stored, null, null, 1024, false); // budget 768

    assertTrue(roomy.trimmedCount() < tight.trimmedCount());
    assertEquals(0, roomy.trimmedCount());
  }

  @Test
  void shouldSubtractTheSystemPromptFromTheBudget() {
    List<AiMessage> stored = history(7); // 728 tokens fit a 768 budget exactly

    PromptPlan without = ContextBuilder.build(stored, null, null, 1024, false);
    PromptPlan with = ContextBuilder.build(stored, null, "s".repeat(300), 1024, false); // -104

    assertEquals(0, without.trimmedCount());
    assertEquals(1, with.trimmedCount());
    assertEquals(104 + 624, with.promptTokensEstimate());
  }

  @Test
  void shouldUseTheSummaryOnlyWhenTheFullHistoryDoesNotFit() {
    List<AiMessage> stored = history(11);
    StoredSummary summary = summary(4, "x".repeat(30)); // prefix + 30 chars

    PromptPlan plan = ContextBuilder.build(stored, summary, null, 1024, false);

    int summaryTokens = TokenEstimator.tokens(ContextBuilder.SUMMARY_PREFIX + summary.text());
    assertTrue(plan.summaryActive());
    assertEquals(summary, plan.summaryUsed());
    // Messages 1-4 are covered by the summary; 5.. are sent as many as fit next to it.
    assertEquals(0, plan.trimmedCount(), "everything after the summary still fits");
    assertEquals(List.of(5, 6, 7, 8, 9, 10, 11), seqs(plan));
    assertEquals(summaryTokens + 728, plan.promptTokensEstimate());
  }

  @Test
  void shouldCountOnlyMessagesBetweenTheSummaryAndTheSentOnesAsTrimmed() {
    List<AiMessage> stored = history(15);
    StoredSummary summary = summary(4, "x".repeat(30));

    PromptPlan plan = ContextBuilder.build(stored, summary, null, 1024, false);

    assertTrue(plan.summaryActive());
    // budget 768 minus the summary leaves 741: 7 messages (9..15); 5..8 are trimmed, 1..4 covered.
    assertEquals(List.of(9, 10, 11, 12, 13, 14, 15), seqs(plan));
    assertEquals(List.of(5, 6, 7, 8), plan.trimmed().stream().map(AiMessage::getSeq).toList());
    assertEquals(4, plan.trimmedCount());
  }

  @Test
  void shouldFallBackToNoSummaryWhenTheSummaryLeavesNoRoomForTheNewestMessage() {
    List<AiMessage> stored = history(11);
    StoredSummary huge = summary(4, "x".repeat(2500)); // ~840 tokens: more than the budget

    PromptPlan plan = ContextBuilder.build(stored, huge, null, 1024, false);

    assertFalse(plan.summaryActive());
    assertEquals(List.of(5, 6, 7, 8, 9, 10, 11), seqs(plan));
  }

  @Test
  void shouldAlwaysIncludeTheNewestUserMessageAndFlagWhenItAloneDoesNotFit() {
    List<AiMessage> stored = new ArrayList<>(history(4));
    stored.add(user(5, "b".repeat(4000))); // 1338 tokens, budget 768

    PromptPlan plan = ContextBuilder.build(stored, null, null, 1024, false);

    assertTrue(plan.tooSmall());
    assertEquals(List.of(5), seqs(plan));
  }

  @Test
  void shouldNotFlagTooSmallWhenTheNewestMessageFitsOnItsOwn() {
    List<AiMessage> stored = new ArrayList<>(history(10));
    stored.add(user(11, BLOCK));

    PromptPlan plan = ContextBuilder.build(stored, null, null, 1024, false);

    assertFalse(plan.tooSmall());
    assertEquals(11, plan.messages().get(plan.messages().size() - 1).getSeq());
  }

  // ---- replay rules ------------------------------------------------------------------------

  @Test
  void shouldSkipErrorStreamingAndEmptyAssistantMessages() {
    List<AiMessage> stored =
        List.of(
            user(1, "q1"),
            message(2, AiMessage.ROLE_ASSISTANT, "partial", AiMessage.STATUS_ERROR),
            user(3, "q2"),
            message(4, AiMessage.ROLE_ASSISTANT, "cut short", AiMessage.STATUS_CANCELLED),
            user(5, "q3"),
            message(6, AiMessage.ROLE_ASSISTANT, "", AiMessage.STATUS_COMPLETE),
            message(7, AiMessage.ROLE_ASSISTANT, "live", AiMessage.STATUS_STREAMING));

    PromptPlan plan = ContextBuilder.build(stored, null, null, 8192, false);

    assertEquals(List.of(1, 3, 4, 5), seqs(plan));
    assertEquals(0, plan.trimmedCount(), "skipped messages are not counted as trimmed");
  }

  @Test
  void shouldNeverPutThinkingIntoThePrompt() {
    AiMessage reply = assistant(2, "answer");
    reply.setThinking("secret reasoning ".repeat(200));
    List<AiMessage> stored = List.of(user(1, "q"), reply, user(3, "q2"));

    PromptPlan plan = ContextBuilder.build(stored, null, null, 8192, false);

    assertEquals(TokenEstimator.tokens("q") + TokenEstimator.tokens("answer") + TokenEstimator.tokens("q2"),
        plan.promptTokensEstimate());
  }

  // ---- images ------------------------------------------------------------------------------

  private static AiMessage withImages(AiMessage message, int images) {
    for (int i = 0; i < images; i++) {
      message.getAttachments().add(new MessageAttachment("a" + i, "image", "image/png", 10, "x.png"));
    }
    return message;
  }

  @Test
  void shouldOmitOlderImagesForAModelWithoutVisionAndCountThem() {
    List<AiMessage> stored =
        List.of(withImages(user(1, "see"), 2), assistant(2, "ok"), withImages(user(3, "and this"), 1));

    PromptPlan plan = ContextBuilder.build(stored, null, null, 8192, false);

    assertEquals(2, plan.imagesOmitted());
    // The newest user message keeps its image cost: the caller rejects it with MODEL_NOT_VISION.
    assertEquals(
        TokenEstimator.tokens("see")
            + TokenEstimator.tokens("ok")
            + TokenEstimator.message("and this", 1),
        plan.promptTokensEstimate());
  }

  @Test
  void shouldCountImageTokensForAVisionModelAndOmitNothing() {
    List<AiMessage> stored = List.of(withImages(user(1, "see"), 2), assistant(2, "ok"), user(3, "and?"));

    PromptPlan plan = ContextBuilder.build(stored, null, null, 8192, true);

    assertEquals(0, plan.imagesOmitted());
    assertEquals(
        TokenEstimator.message("see", 2) + TokenEstimator.tokens("ok") + TokenEstimator.tokens("and?"),
        plan.promptTokensEstimate());
  }

  @Test
  void shouldHandleAnEmptyHistory() {
    PromptPlan plan = ContextBuilder.build(List.of(), null, null, 4096, true);

    assertTrue(plan.messages().isEmpty());
    assertEquals(0, plan.promptTokensEstimate());
    assertTrue(plan.fits());
    assertFalse(plan.tooSmall());
  }
}
