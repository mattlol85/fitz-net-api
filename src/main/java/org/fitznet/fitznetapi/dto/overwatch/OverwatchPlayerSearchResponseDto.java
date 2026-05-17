package org.fitznet.fitznetapi.dto.overwatch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverwatchPlayerSearchResponseDto {
  private Integer total;
  private List<OverwatchPlayerSearchResultDto> results;
}

