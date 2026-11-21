package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import org.fitznet.fitznetapi.dto.requests.UploadRecipeImageRequestDto;
import org.fitznet.fitznetapi.dto.responses.ImageContentDto;
import org.fitznet.fitznetapi.dto.responses.RecipeImageDto;
import org.fitznet.fitznetapi.service.recipes.RecipeImageService;
import org.fitznet.fitznetapi.util.HttpCaching;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recipe cover photos: upload first, then reference the id as a recipe's {@code imageId}.
 * Owner-only; needs {@code LORINET_ACCESS} or {@code ADMIN}. The gRPC {@code RecipeImageService}
 * calls the byte-oriented overloads of the same methods.
 */
@RestController
@RequestMapping("/recipe-images")
@PreAuthorize("hasAnyAuthority('PERM_LORINET_ACCESS','PERM_ADMIN')")
public class RecipeImageController {

  private final RecipeImageService recipeImageService;

  public RecipeImageController(RecipeImageService recipeImageService) {
    this.recipeImageService = recipeImageService;
  }

  @PostMapping
  public RecipeImageDto uploadRecipeImage(@RequestBody @Valid UploadRecipeImageRequestDto request) {
    byte[] data = recipeImageService.decodeBase64(request.getDataBase64());
    return recipeImageService.upload(currentUsername(), request.getMimeType(), data);
  }

  /** gRPC {@code UploadRecipeImage}: the same checks on raw bytes. */
  public RecipeImageDto uploadRecipeImage(String mimeType, byte[] data) {
    return recipeImageService.upload(currentUsername(), mimeType, data);
  }

  /** The raw bytes with the image's {@code Content-Type}; images never change once stored. */
  @GetMapping("/{id}")
  public ResponseEntity<byte[]> getRecipeImage(@PathVariable String id) {
    ImageContentDto content = readImage(id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(content.mimeType()))
        .header(HttpHeaders.CACHE_CONTROL, HttpCaching.PRIVATE_IMMUTABLE)
        .body(content.data());
  }

  /** gRPC {@code GetRecipeImage}: the mime type and bytes. */
  public ImageContentDto readImage(String id) {
    return recipeImageService.read(currentUsername(), id);
  }

  private static String currentUsername() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
