package org.fitznet.fitznetapi.model;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Document("overwatch_rating_snapshots")
@CompoundIndex(name = "userId_season_recordedAt", def = "{'userId': 1, 'season': 1, 'recordedAt': 1}")
public class OverwatchRatingSnapshot {

  @Id String id;

  String userId;
  String season;
  Instant recordedAt;

  Integer dpsRating;
  Integer tankRating;
  Integer healsRating;
}
