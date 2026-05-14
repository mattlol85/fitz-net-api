package org.fitznet.fitznetapi.dto.overwatch;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverwatchPlayerSearchResultDto {
  @JsonAlias({"playerId", "player_id"})
  @JsonProperty("playerId")
  private String playerId;

  private String name;
  private String avatar;
  private String privacy;

  @JsonAlias({"careerUrl", "career_url"})
  @JsonProperty("careerUrl")
  private String careerUrl;
}
