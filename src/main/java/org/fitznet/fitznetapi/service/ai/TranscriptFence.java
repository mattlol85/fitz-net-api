package org.fitznet.fitznetapi.service.ai;

import java.util.regex.Pattern;

/**
 * Puts conversation text between explicit delimiters before it is handed to a model that is asked
 * to summarise or title it. The instructions tell the model that everything between the delimiters
 * is material to work on, never instructions; this class makes sure the text cannot close the
 * fence early by containing the delimiter itself.
 */
final class TranscriptFence {

  static final String OPEN = "<transcript>";
  static final String CLOSE = "</transcript>";

  private static final Pattern FENCE_TAG = Pattern.compile("</?transcript>", Pattern.CASE_INSENSITIVE);

  private TranscriptFence() {}

  static String wrap(String body) {
    String safe = FENCE_TAG.matcher(body).replaceAll(match -> match.group().startsWith("</") ? "[/transcript]" : "[transcript]");
    return OPEN + "\n" + safe + "\n" + CLOSE;
  }
}
