package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code PUT /recipe-book}. {@code chapters} replaces the whole chapter list in book order;
 * recipes in a chapter that is no longer listed move to Unsorted.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRecipeBookRequestDto {

  public static final int MAX_CHAPTERS = 20;

  @NotBlank(message = "title must not be blank")
  @Size(max = 60, message = "title must be at most 60 characters")
  String title;

  @NotBlank(message = "coverColor must not be blank")
  @Pattern(
      regexp = "rose|burgundy|forest|navy|tan|charcoal",
      message = "coverColor must be one of rose, burgundy, forest, navy, tan, charcoal")
  String coverColor;

  @NotNull(message = "chapters must not be null")
  @Size(max = MAX_CHAPTERS, message = "a book has at most 20 chapters")
  List<@Valid @NotNull RecipeChapterRequestDto> chapters;
}
