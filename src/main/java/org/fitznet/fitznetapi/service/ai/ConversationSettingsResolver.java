package org.fitznet.fitznetapi.service.ai;

import java.util.Optional;
import org.fitznet.fitznetapi.model.AiConversation;
import org.fitznet.fitznetapi.model.AiPreset;
import org.springframework.stereotype.Component;

/**
 * Turns a conversation's settings into what generation uses (contract section 9). Values are read
 * at generation time, not snapshotted: the system prompt is {@code settings.systemPrompt} if set,
 * else the preset's; temperature and {@code numCtx} come from the preset. A deleted preset, or one
 * the owner can no longer see, is treated as none (the turn does not fail).
 */
@Component
public class ConversationSettingsResolver {

  private final PresetService presets;

  public ConversationSettingsResolver(PresetService presets) {
    this.presets = presets;
  }

  public EffectiveSettings resolve(AiConversation conversation) {
    Optional<AiPreset> preset =
        presets.findVisible(conversation.getOwnerUsername(), conversation.getPresetId());
    String override = conversation.getSystemPrompt();
    String systemPrompt =
        override != null && !override.isBlank()
            ? override
            : preset.map(AiPreset::getSystemPrompt).filter(s -> !s.isBlank()).orElse(null);
    return new EffectiveSettings(
        systemPrompt,
        preset.map(AiPreset::getNumCtx).orElse(null),
        preset.map(AiPreset::getTemperature).orElse(null),
        preset.map(AiPreset::getId).orElse(null),
        preset.map(AiPreset::getName).orElse(null));
  }
}
