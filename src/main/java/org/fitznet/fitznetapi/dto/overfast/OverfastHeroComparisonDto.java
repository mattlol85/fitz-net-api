package org.fitznet.fitznetapi.dto.overfast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverfastHeroComparisonDto {
  private String label;
  private List<OverfastHeroValueDto> values;
}
