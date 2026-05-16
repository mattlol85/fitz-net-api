package org.fitznet.fitznetapi.dto.overwatch;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverwatchPlayerSummaryDto {
  @JsonAlias({"playerId", "player_id"})
  @JsonProperty("playerId")
  private String playerId;

  private String name;
  private String avatar;
}
