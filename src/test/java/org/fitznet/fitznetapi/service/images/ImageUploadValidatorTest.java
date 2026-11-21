package org.fitznet.fitznetapi.service.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ImageUploadValidatorTest {

  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1};
  private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 1};

  private static void assertBadRequest(String message, Runnable call) {
    ResponseStatusException ex = assertThrows(ResponseStatusException.class, call::run);
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    assertEquals(message, ex.getReason());
  }

  @Test
  void shouldDecodeValidBase64() {
    String encoded = Base64.getEncoder().encodeToString(PNG);
    assertArrayEquals(PNG, ImageUploadValidator.decodeBase64(encoded, 1000));
  }

  @Test
  void shouldRejectEmptyInvalidAndOversizedBase64() {
    assertBadRequest(
        "dataBase64 must not be empty", () -> ImageUploadValidator.decodeBase64(" ", 1000));
    assertBadRequest(
        "dataBase64 is not valid base64", () -> ImageUploadValidator.decodeBase64("@@@@", 1000));
    String big = Base64.getEncoder().encodeToString(new byte[100]);
    assertBadRequest(
        "The image is larger than 10 bytes", () -> ImageUploadValidator.decodeBase64(big, 10));
  }

  @Test
  void shouldAcceptEachSupportedFormatByMagicNumber() {
    assertEquals(ImageFormat.PNG, ImageUploadValidator.requireSupportedImage(PNG, "image/png", 100));
    assertEquals(
        ImageFormat.JPEG, ImageUploadValidator.requireSupportedImage(JPEG, "image/jpeg", 100));
    assertEquals(
        ImageFormat.WEBP, ImageUploadValidator.requireSupportedImage(WEBP, "image/webp", 100));
  }

  @Test
  void shouldRejectUnsupportedEmptyOversizedAndMismatchedImages() {
    assertBadRequest(
        "Only image/png, image/jpeg and image/webp are accepted",
        () -> ImageUploadValidator.requireSupportedImage(PNG, "image/gif", 100));
    assertBadRequest(
        "The image is empty",
        () -> ImageUploadValidator.requireSupportedImage(new byte[0], "image/png", 100));
    assertBadRequest(
        "The image is larger than 4 bytes",
        () -> ImageUploadValidator.requireSupportedImage(PNG, "image/png", 4));
    assertBadRequest(
        "The data is not a valid image/jpeg image",
        () -> ImageUploadValidator.requireSupportedImage(PNG, "image/jpeg", 100));
  }

  @Test
  void shouldResolveOnlyExactMimeTypes() {
    assertEquals(ImageFormat.WEBP, ImageFormat.fromMimeType("image/webp"));
    assertNull(ImageFormat.fromMimeType("IMAGE/PNG"));
    assertNull(ImageFormat.fromMimeType(null));
  }
}
