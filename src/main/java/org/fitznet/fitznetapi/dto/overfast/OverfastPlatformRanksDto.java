package org.fitznet.fitznetapi.dto.overfast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverfastPlatformRanksDto {
  private OverfastRoleRankDto tank;
  private OverfastRoleRankDto damage;
  private OverfastRoleRankDto support;
  private OverfastRoleRankDto open;
}

