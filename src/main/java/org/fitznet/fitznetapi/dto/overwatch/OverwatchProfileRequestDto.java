package org.fitznet.fitznetapi.dto.overwatch;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OverwatchProfileRequestDto {
  @NotBlank
  @JsonAlias({"playerId", "player_id", "battleTag", "battle_tag", "bnetString", "bnet_string"})
  @JsonProperty("playerId")
  private String playerId;

  private String gamemode;
  private String platform;
}
