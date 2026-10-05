package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.NotNull;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePermissionsRequestDto {
  @NotNull Set<String> permissions;
}
