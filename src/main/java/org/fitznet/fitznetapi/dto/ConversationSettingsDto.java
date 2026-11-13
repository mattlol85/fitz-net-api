package org.fitznet.fitznetapi.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-conversation settings: a preset reference and a system prompt override. Replaced whole when
 * sent in a PATCH, so {@code {presetId: null, systemPrompt: null}} clears both.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSettingsDto {

  public static final int MAX_SYSTEM_PROMPT_CHARS = 8000;

  @Size(max = 64)
  String presetId;

  @Size(max = MAX_SYSTEM_PROMPT_CHARS, message = "system prompt is too long")
  String systemPrompt;
}
