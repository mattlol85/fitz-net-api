package org.fitznet.fitznetapi.dto.requests;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.fitznet.fitznetapi.dto.ConversationSettingsDto;
import org.fitznet.fitznetapi.dto.ModelTargetDto;

/**
 * {@code PATCH /ai/conversations/{id}}. Fields are independent: an absent field is left alone.
 * {@code target} and {@code settings} are replaced whole when present, and an explicit {@code null}
 * clears them. {@code title} and {@code pinned} ignore a null.
 */
public class UpdateConversationRequestDto {

  @Size(max = CreateConversationRequestDto.MAX_TITLE_CHARS, message = "title is too long")
  private String title;

  private Boolean pinned;

  @Valid private ModelTargetDto target;
  private boolean targetPresent;

  @Valid private ConversationSettingsDto settings;
  private boolean settingsPresent;

  public UpdateConversationRequestDto() {}

  /** For callers that build the request in code (gRPC): presence is explicit. */
  public UpdateConversationRequestDto(
      String title,
      Boolean pinned,
      boolean targetPresent,
      ModelTargetDto target,
      boolean settingsPresent,
      ConversationSettingsDto settings) {
    this.title = title;
    this.pinned = pinned;
    this.targetPresent = targetPresent;
    this.target = target;
    this.settingsPresent = settingsPresent;
    this.settings = settings;
  }

  @JsonProperty("title")
  public String getTitle() {
    return title;
  }

  @JsonProperty("title")
  public void setTitle(String title) {
    this.title = title;
  }

  @JsonProperty("pinned")
  public Boolean getPinned() {
    return pinned;
  }

  @JsonProperty("pinned")
  public void setPinned(Boolean pinned) {
    this.pinned = pinned;
  }

  @JsonProperty("target")
  public ModelTargetDto getTarget() {
    return target;
  }

  @JsonSetter("target")
  public void setTarget(ModelTargetDto target) {
    this.target = target;
    this.targetPresent = true;
  }

  @JsonIgnore
  public boolean isTargetPresent() {
    return targetPresent;
  }

  @JsonProperty("settings")
  public ConversationSettingsDto getSettings() {
    return settings;
  }

  @JsonSetter("settings")
  public void setSettings(ConversationSettingsDto settings) {
    this.settings = settings;
    this.settingsPresent = true;
  }

  @JsonIgnore
  public boolean isSettingsPresent() {
    return settingsPresent;
  }
}
