package org.fitznet.fitznetapi.dto.liveboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CursorMoveDto {
  private String username;
  @JsonProperty("xRatio")
  private double xRatio;
  @JsonProperty("yRatio")
  private double yRatio;
  private Boolean painting;
  private String color;
}

