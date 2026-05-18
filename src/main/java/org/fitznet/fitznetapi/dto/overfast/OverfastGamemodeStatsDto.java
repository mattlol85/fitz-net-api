package org.fitznet.fitznetapi.dto.overfast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverfastGamemodeStatsDto {
  @JsonProperty("heroes_comparisons")
  private OverfastHeroesComparisonsDto heroesComparisons;
}
