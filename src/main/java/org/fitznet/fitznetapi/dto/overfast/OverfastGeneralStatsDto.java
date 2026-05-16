package org.fitznet.fitznetapi.dto.overfast;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverfastGeneralStatsDto {
  @JsonAlias("games_played")
  private Integer gamesPlayed;

  @JsonAlias("games_won")
  private Integer gamesWon;

  @JsonAlias("games_lost")
  private Integer gamesLost;

  @JsonAlias("time_played")
  private Integer timePlayed;

  private Double winrate;
  private Double kda;

  private OverfastStatsTotalsDto total;
}

