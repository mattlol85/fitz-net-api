package org.fitznet.fitznetapi.model.overwatch;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

/**
 * Overwatch tracker data owned 1:1 by a {@link org.fitznet.fitznetapi.model.User}. Persisted as an
 * embedded sub-document under the {@code overwatch} field of the {@code users} collection — it has
 * no independent lifecycle and is never queried on its own. Time-series rating history lives
 * separately in {@link org.fitznet.fitznetapi.model.OverwatchRatingSnapshot}.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class OverwatchProfile {

  String playerId;
  String battleTag;
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

  // Role-specific competitive ratings
  Integer dpsRating;
  Integer tankRating;
  Integer healsRating;

  // All-time peak competitive ratings
  Integer dpsPeakRating;
  Integer tankPeakRating;
  Integer healsPeakRating;
}
