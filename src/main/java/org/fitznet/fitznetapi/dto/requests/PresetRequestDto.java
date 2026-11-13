package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /ai/presets} and {@code PUT /ai/presets/{id}} (a PUT replaces the preset).
 * {@code nodeId} is only meaningful with a {@code model}, and {@code requiredPermission} only for a
 * shared preset; the service rejects those combinations with a 400.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PresetRequestDto {

  public static final int MAX_NAME_CHARS = 60;
  public static final int MAX_DESCRIPTION_CHARS = 200;
  public static final int MAX_SYSTEM_PROMPT_CHARS = 8000;

  @NotBlank
  @Size(max = MAX_NAME_CHARS, message = "name must be at most 60 characters")
  String name;

  @Size(max = MAX_DESCRIPTION_CHARS, message = "description must be at most 200 characters")
  String description;

  @Size(max = 200)
  String model;

  @Size(max = 64)
  String nodeId;

  @Size(max = MAX_SYSTEM_PROMPT_CHARS, message = "system prompt must be at most 8000 characters")
  String systemPrompt;

  @DecimalMin(value = "0.0", message = "temperature must be between 0 and 2")
  @DecimalMax(value = "2.0", message = "temperature must be between 0 and 2")
  Double temperature;

  @Min(value = 512, message = "numCtx must be between 512 and 131072")
  @Max(value = 131072, message = "numCtx must be between 512 and 131072")
  Integer numCtx;

  /** {@code private} (default) or {@code shared}. */
  @Pattern(regexp = "private|shared", message = "visibility must be 'private' or 'shared'")
  String visibility;

  @Size(max = 64)
  String requiredPermission;
}
