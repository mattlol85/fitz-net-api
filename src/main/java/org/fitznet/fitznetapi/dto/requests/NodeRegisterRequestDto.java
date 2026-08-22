package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodeRegisterRequestDto {
  @NotBlank String token;
  @NotBlank String name;
  String os;
  List<String> models;
  Double vramGb;
}
