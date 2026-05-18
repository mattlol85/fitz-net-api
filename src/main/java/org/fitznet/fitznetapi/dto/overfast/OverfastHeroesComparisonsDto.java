package org.fitznet.fitznetapi.dto.overfast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverfastHeroesComparisonsDto {
  @JsonProperty("time_played")
  private OverfastHeroComparisonDto timePlayed;

  @JsonProperty("games_won")
  private OverfastHeroComparisonDto gamesWon;

  @JsonProperty("win_percentage")
  private OverfastHeroComparisonDto winPercentage;
}
