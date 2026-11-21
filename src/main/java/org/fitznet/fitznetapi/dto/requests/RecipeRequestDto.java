package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /recipes} and {@code PUT /recipes/{id}} (a PUT replaces the recipe; its
 * {@code createdAt} never changes). {@code chapterId} must be a chapter of the caller's book (null =
 * Unsorted) and {@code imageId} an image the caller uploaded that no other recipe uses; the service
 * checks both.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeRequestDto {

  public static final int MAX_INGREDIENTS = 100;
  public static final int MAX_STEPS = 50;

  @NotBlank(message = "title must not be blank")
  @Size(max = 120, message = "title must be at most 120 characters")
  String title;

  @Size(max = 64)
  String chapterId;

  @Min(value = 1, message = "servings must be between 1 and 100")
  @Max(value = 100, message = "servings must be between 1 and 100")
  Integer servings;

  @Min(value = 0, message = "prepMinutes must be between 0 and 1440")
  @Max(value = 1440, message = "prepMinutes must be between 0 and 1440")
  Integer prepMinutes;

  @Min(value = 0, message = "cookMinutes must be between 0 and 1440")
  @Max(value = 1440, message = "cookMinutes must be between 0 and 1440")
  Integer cookMinutes;

  @NotNull(message = "ingredients must not be null")
  @Size(min = 1, max = MAX_INGREDIENTS, message = "a recipe needs 1 to 100 ingredients")
  List<@Valid @NotNull RecipeIngredientDto> ingredients;

  @NotNull(message = "instructions must not be null")
  @Size(min = 1, max = MAX_STEPS, message = "a recipe needs 1 to 50 steps")
  List<
          @NotBlank(message = "a step must not be blank")
          @Size(max = 2000, message = "a step must be at most 2000 characters")
          String>
      instructions;

  @Size(max = 64)
  String imageId;
}
