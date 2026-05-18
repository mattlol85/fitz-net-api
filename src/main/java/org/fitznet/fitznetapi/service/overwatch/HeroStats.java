package org.fitznet.fitznetapi.service.overwatch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HeroStats {
  private String heroKey;
  private Long timePlayed;
  private Integer gamesWon;
  private Double winPercentage;
}
