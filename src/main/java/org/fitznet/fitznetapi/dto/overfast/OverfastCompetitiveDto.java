package org.fitznet.fitznetapi.dto.overfast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverfastCompetitiveDto {
  private OverfastPlatformRanksDto pc;
  private OverfastPlatformRanksDto console;
}

