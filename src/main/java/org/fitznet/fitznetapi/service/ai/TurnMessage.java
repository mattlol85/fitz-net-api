package org.fitznet.fitznetapi.service.ai;

import java.util.List;

/**
 * One message of the prompt sent to Ollama.
 *
 * @param role {@code system}, {@code user} or {@code assistant}
 * @param imagesBase64 base64 images for a vision model; empty for none
 */
public record TurnMessage(String role, String content, List<String> imagesBase64) {

  public TurnMessage {
    imagesBase64 = imagesBase64 == null ? List.of() : List.copyOf(imagesBase64);
  }

  public static TurnMessage of(String role, String content) {
    return new TurnMessage(role, content, List.of());
  }
}
