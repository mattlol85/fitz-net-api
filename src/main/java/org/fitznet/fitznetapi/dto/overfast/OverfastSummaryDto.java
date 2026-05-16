package org.fitznet.fitznetapi.dto.overfast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverfastSummaryDto {
  private String username;
  private String avatar;
  private String namecard;
  private String title;
  private String privacy;
  private OverfastCompetitiveDto competitive;
}

