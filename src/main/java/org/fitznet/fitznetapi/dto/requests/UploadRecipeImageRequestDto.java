package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code POST /recipe-images}: a cover photo as base64 (gRPC sends the raw bytes instead). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UploadRecipeImageRequestDto {

  @NotBlank String mimeType;

  @NotBlank String dataBase64;
}
