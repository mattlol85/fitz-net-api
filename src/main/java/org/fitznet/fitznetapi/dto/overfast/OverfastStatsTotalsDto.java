package org.fitznet.fitznetapi.dto.overfast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverfastStatsTotalsDto {
  private Integer eliminations;
  private Integer assists;
  private Integer deaths;
  private Integer damage;
  private Integer healing;
}
