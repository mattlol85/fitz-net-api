package org.fitznet.fitznetapi.dto.requests;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRequestDto {
    String username;
    String updatedUsername;
    String email;
    String updatedEmail;
}
