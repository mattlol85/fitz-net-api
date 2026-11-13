package org.fitznet.fitznetapi.service.ai;

/**
 * The server has no tokenizer, so it estimates conservatively (contract section 8):
 * {@code ceil(codePoints / 3) + 4} per message and 768 per image.
 */
public final class TokenEstimator {

  public static final int IMAGE_TOKENS = 768;
  private static final int PER_MESSAGE_OVERHEAD = 4;

  private TokenEstimator() {}

  /** Estimated tokens of one message with this text and no images. */
  public static int tokens(String text) {
    int codePoints = text == null ? 0 : text.codePointCount(0, text.length());
    return (codePoints + 2) / 3 + PER_MESSAGE_OVERHEAD;
  }

  /** Estimated tokens of one message with {@code images} images that are actually sent. */
  public static int message(String text, int images) {
    return tokens(text) + images * IMAGE_TOKENS;
  }
}
