package org.fitznet.fitznetapi.service.ai;

import org.fitznet.fitznetapi.model.AiConversation;
import org.springframework.stereotype.Component;

/**
 * Turns a conversation's settings into what generation actually uses. This is the one place presets
 * plug in (part 3): the system prompt becomes {@code settings.systemPrompt} if set, else the
 * preset's, and the preset's {@code numCtx} caps the window via {@link RoutedTarget#withContextCap}.
 * For now only the per-conversation system prompt applies.
 */
@Component
public class ConversationSettingsResolver {

  /** The system prompt to send, or null for none. */
  public String systemPrompt(AiConversation conversation) {
    String prompt = conversation.getSystemPrompt();
    return prompt == null || prompt.isBlank() ? null : prompt;
  }

  /** The routed target with any conversation-specific window cap applied (none yet). */
  public RoutedTarget apply(RoutedTarget target, AiConversation conversation) {
    return target;
  }

  /** Temperature from the preset, or null for the model default (no presets yet). */
  public Double temperature(AiConversation conversation) {
    return null;
  }

  /** Preset id recorded on the generated message (null until presets exist). */
  public String presetId(AiConversation conversation) {
    return null;
  }

  /** Preset name recorded on the generated message (null until presets exist). */
  public String presetName(AiConversation conversation) {
    return null;
  }
}
