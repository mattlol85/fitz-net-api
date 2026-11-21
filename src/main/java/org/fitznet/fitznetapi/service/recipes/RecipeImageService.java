package org.fitznet.fitznetapi.service.recipes;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.fitznet.fitznetapi.dto.responses.ImageContentDto;
import org.fitznet.fitznetapi.dto.responses.RecipeImageDto;
import org.fitznet.fitznetapi.model.RecipeImage;
import org.fitznet.fitznetapi.repository.RecipeImageRepository;
import org.fitznet.fitznetapi.service.images.ImageDimensions;
import org.fitznet.fitznetapi.service.images.ImageFormat;
import org.fitznet.fitznetapi.service.images.ImageUploadValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Recipe cover photos. An upload is stored unclaimed with {@code expiresAt} set (the
 * {@code expires_at_ttl} index removes it after {@code recipes.image-upload-ttl} if no recipe uses
 * it); saving a recipe that references it claims it ({@code recipeId} set, {@code expiresAt}
 * unset). Format and size checks are the shared {@link ImageUploadValidator}'s, capped at
 * {@code recipes.max-image-bytes}. Owner-only: anyone else gets a 404. Bytes are never logged.
 */
@Service
public class RecipeImageService {

  private static final Logger logger = LoggerFactory.getLogger(RecipeImageService.class);

  private final RecipeImageRepository images;
  private final MongoTemplate mongoTemplate;
  private final int maxBytes;
  private final Duration uploadTtl;

  public RecipeImageService(
      RecipeImageRepository images,
      MongoTemplate mongoTemplate,
      @Value("${recipes.max-image-bytes:2097152}") int maxBytes,
      @Value("${recipes.image-upload-ttl:PT1H}") Duration uploadTtl) {
    this.images = images;
    this.mongoTemplate = mongoTemplate;
    this.maxBytes = maxBytes;
    this.uploadTtl = uploadTtl;
  }

  /** Decodes the REST body's base64; 400 when it is invalid or obviously larger than the limit. */
  public byte[] decodeBase64(String dataBase64) {
    return ImageUploadValidator.decodeBase64(dataBase64, maxBytes);
  }

  public RecipeImageDto upload(String username, String mimeType, byte[] data) {
    ImageFormat format = ImageUploadValidator.requireSupportedImage(data, mimeType, maxBytes);
    ImageDimensions dims = ImageDimensions.read(format, data);
    Instant now = now();
    RecipeImage saved =
        images.save(
            RecipeImage.builder()
                .ownerUsername(username)
                .mimeType(format.mimeType())
                .sizeBytes(data.length)
                .width(dims == null ? null : dims.width())
                .height(dims == null ? null : dims.height())
                .data(data)
                .createdAt(now)
                .expiresAt(now.plus(uploadTtl))
                .build());
    logger.info(
        "Stored recipe image {} ({} bytes, {})", saved.getId(), saved.getSizeBytes(), saved.getMimeType());
    return toDto(saved);
  }

  /** The bytes and content type of the caller's own image (404 otherwise). */
  public ImageContentDto read(String username, String id) {
    RecipeImage image =
        images
            .findByIdAndOwnerUsername(id, username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));
    return new ImageContentDto(image.getMimeType(), image.getData());
  }

  /**
   * Claims the caller's image for {@code recipeId}: it must exist, belong to the caller and be
   * either unclaimed or already that recipe's (400 otherwise). Stops its expiry.
   */
  public void claim(String username, String imageId, String recipeId) {
    RecipeImage image =
        images.findByIdAndOwnerUsername(imageId, username).orElseThrow(() -> badRequest("Unknown image id"));
    if (image.getRecipeId() != null && !image.getRecipeId().equals(recipeId)) {
      throw badRequest("Image is already used by another recipe");
    }
    // Conditional, so two recipes saved at once can never both claim the same upload.
    Query unclaimedOrOurs =
        Query.query(
            new Criteria()
                .andOperator(
                    Criteria.where("_id").is(image.getId()),
                    Criteria.where("ownerUsername").is(username),
                    new Criteria()
                        .orOperator(
                            Criteria.where("recipeId").is(null),
                            Criteria.where("recipeId").is(recipeId))));
    long matched =
        mongoTemplate
            .updateFirst(
                unclaimedOrOurs,
                new Update().set("recipeId", recipeId).unset("expiresAt"),
                RecipeImage.class)
            .getMatchedCount();
    if (matched == 0) {
      throw badRequest("Image is already used by another recipe");
    }
  }

  public void delete(String imageId) {
    images.deleteById(imageId);
  }

  public void deleteForRecipe(String recipeId) {
    images.deleteByRecipeId(recipeId);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static RecipeImageDto toDto(RecipeImage image) {
    return new RecipeImageDto(
        image.getId(),
        image.getMimeType(),
        image.getSizeBytes(),
        image.getWidth(),
        image.getHeight(),
        image.getCreatedAt());
  }

  /** Mongo stores milliseconds; truncating keeps a write's response equal to later reads. */
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }
}
