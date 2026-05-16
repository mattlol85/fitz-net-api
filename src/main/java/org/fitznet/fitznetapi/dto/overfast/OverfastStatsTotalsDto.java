package org.fitznet.fitznetapi.dto.overfast;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverfastStatsTotalsDto {
  private Integer eliminations;
  private Integer deaths;
  private Integer damage;
  private Integer healing;

  @JsonAlias("final_blows")
  private Integer finalBlows;

  @JsonAlias({"wins", "games_won"})
  private Integer wins;

  @JsonAlias("games_played")
  private Integer gamesPlayed;
}

