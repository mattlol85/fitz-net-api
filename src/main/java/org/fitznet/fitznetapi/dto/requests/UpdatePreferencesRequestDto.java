package org.fitznet.fitznetapi.dto.requests;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.fitznet.fitznetapi.dto.ModelTargetDto;

/**
 * {@code PUT /ai/preferences}. Presence matters: a field that is absent from the body is left
 * alone, a field sent as {@code null} is cleared, and a field with a value replaces the stored one.
 * (A client that only changes the picker therefore never wipes {@code lastPresetId}.)
 */
public class UpdatePreferencesRequestDto {

  @Valid private ModelTargetDto lastTarget;
  private boolean lastTargetPresent;

  @Size(max = 64)
  private String lastPresetId;

  private boolean lastPresetIdPresent;

  public UpdatePreferencesRequestDto() {}

  /** For callers that build the request in code (gRPC): presence is explicit. */
  public UpdatePreferencesRequestDto(
      boolean lastTargetPresent,
      ModelTargetDto lastTarget,
      boolean lastPresetIdPresent,
      String lastPresetId) {
    this.lastTargetPresent = lastTargetPresent;
    this.lastTarget = lastTarget;
    this.lastPresetIdPresent = lastPresetIdPresent;
    this.lastPresetId = lastPresetId;
  }

  @JsonProperty("lastTarget")
  public ModelTargetDto getLastTarget() {
    return lastTarget;
  }

  @JsonSetter("lastTarget")
  public void setLastTarget(ModelTargetDto lastTarget) {
    this.lastTarget = lastTarget;
    this.lastTargetPresent = true;
  }

  @JsonIgnore
  public boolean isLastTargetPresent() {
    return lastTargetPresent;
  }

  @JsonProperty("lastPresetId")
  public String getLastPresetId() {
    return lastPresetId;
  }

  @JsonSetter("lastPresetId")
  public void setLastPresetId(String lastPresetId) {
    this.lastPresetId = lastPresetId;
    this.lastPresetIdPresent = true;
  }

  @JsonIgnore
  public boolean isLastPresetIdPresent() {
    return lastPresetIdPresent;
  }
}
