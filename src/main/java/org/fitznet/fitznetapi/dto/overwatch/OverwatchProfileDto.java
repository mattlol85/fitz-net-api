package org.fitznet.fitznetapi.dto.overwatch;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OverwatchProfileDto {
  String username;
  String playerId;
  String displayName;
  String avatarUrl;
  Instant lastUpdatedAt;
  Integer gamesWon;
  Integer gamesPlayed;
  Double winrate;
  Double kda;
  Integer eliminations;
  Integer deaths;
  Integer damage;
  Integer healing;
}
