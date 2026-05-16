package org.fitznet.fitznetapi.dto.overwatch;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OverwatchSeasonHistoryPointDto {
  String label;
  Integer rating;
  Instant recordedAt;
}

