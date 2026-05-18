package org.fitznet.fitznetapi.dto.overwatch;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class HeroTimelineDto {
  String heroKey;
  String heroName;
  List<HeroDataPointDto> history;
}
