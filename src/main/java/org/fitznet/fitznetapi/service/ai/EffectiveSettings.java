package org.fitznet.fitznetapi.service.ai;

/**
 * What generation actually uses for a conversation, resolved at generation time from the
 * conversation's own settings and its preset (if it still exists and is still visible).
 *
 * @param systemPrompt the conversation override if set, else the preset's; null for none
 * @param numCtxCap the preset's {@code numCtx}, or null; caps the window like
 *     {@code ollama.max-num-ctx} does
 * @param temperature the preset's temperature, or null for the model default
 * @param presetId the preset in effect, or null (also when the stored preset is gone)
 * @param presetName its name at this moment
 */
public record EffectiveSettings(
    String systemPrompt, Integer numCtxCap, Double temperature, String presetId, String presetName) {

  /** The routed target with the window capped at the preset's {@code numCtx}. */
  public RoutedTarget apply(RoutedTarget target) {
    return target.withContextCap(numCtxCap);
  }
}
