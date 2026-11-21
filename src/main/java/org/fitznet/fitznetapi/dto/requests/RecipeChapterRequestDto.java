package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A chapter in {@code PUT /recipe-book}. Without an {@code id} (or with one the book does not have)
 * it is a new chapter and the server assigns the id.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeChapterRequestDto {

  @Size(max = 64)
  String id;

  @NotBlank(message = "chapter name must not be blank")
  @Size(max = 40, message = "chapter name must be at most 40 characters")
  String name;

  /** A lowercase colour name ({@code sage}, {@code dusty-rose}) or a {@code #rrggbb} hex colour. */
  @NotBlank(message = "tabColor must not be blank")
  @Pattern(
      regexp = "[a-z][a-z0-9-]{0,19}|#[0-9a-fA-F]{6}",
      message = "tabColor must be a lowercase colour name or #rrggbb")
  String tabColor;
}
