package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code POST /ai/attachments}: an image as base64 (gRPC sends the raw bytes instead). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UploadAttachmentRequestDto {

  @NotBlank
  @Size(max = 200)
  String name;

  @NotBlank String mimeType;

  @NotBlank String dataBase64;
}
