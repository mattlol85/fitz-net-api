package org.fitznet.fitznetapi.dto.responses;

import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPermissionsResponseDto {
  String username;
  Set<String> permissions;
}
