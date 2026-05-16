package org.fitznet.fitznetapi.dto.overwatch;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OverwatchStatsSnapshotDto {
  Integer gamesWon;
  Integer gamesPlayed;
  Double winrate;
  Double kda;
  Integer eliminations;
  Integer deaths;
  Integer damage;
  Integer healing;
}
