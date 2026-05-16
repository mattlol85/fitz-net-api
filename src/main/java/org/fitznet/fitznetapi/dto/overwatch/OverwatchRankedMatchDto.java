package org.fitznet.fitznetapi.dto.overwatch;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OverwatchRankedMatchDto {
  String result;
  Boolean win;
  String mode;
  String map;
  Integer scoreFor;
  Integer scoreAgainst;
  Instant playedAt;
}

