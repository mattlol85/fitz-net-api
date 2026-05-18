package org.fitznet.fitznetapi.dto.overwatch;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class HeroDataPointDto {
  String label;
  Double winRate;
  Instant recordedAt;
}
