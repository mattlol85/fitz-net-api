package org.fitznet.fitznetapi.dto.liveboard;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CursorMoveDto {
  private String username;
  private double xRatio;
  private double yRatio;
  private Boolean painting;
  private String color;
}

