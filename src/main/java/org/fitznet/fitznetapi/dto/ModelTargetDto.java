package org.fitznet.fitznetapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What to run a chat on: a model, on a specific node or (null nodeId) wherever Auto routes. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelTargetDto {
  @NotBlank
  @Size(max = 200)
  String model;

  @Size(max = 64)
  String nodeId;
}
