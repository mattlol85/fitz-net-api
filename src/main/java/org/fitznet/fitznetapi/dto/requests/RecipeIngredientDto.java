package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One ingredient row of a recipe, in requests and in {@code RecipeDto}. Only {@code name} is
 * required; the quantity is free text ({@code "1 1/2"}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecipeIngredientDto {

  @Size(max = 20, message = "quantity must be at most 20 characters")
  String quantity;

  @Size(max = 20, message = "unit must be at most 20 characters")
  String unit;

  @NotBlank(message = "ingredient name must not be blank")
  @Size(max = 80, message = "ingredient name must be at most 80 characters")
  String name;

  @Size(max = 120, message = "note must be at most 120 characters")
  String note;
}
