package org.fitznet.fitznetapi.dto.overfast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverfastPlayerStatsWrapDto {
  private OverfastPlatformStatsDto pc;
  private OverfastPlatformStatsDto console;
}
