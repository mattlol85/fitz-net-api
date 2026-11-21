package org.fitznet.fitznetapi.service.images;

import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Feature-neutral checks for an uploaded image: base64 decoding with an oversize check before the
 * decoded copy is allocated, and format sniffing (only png, jpeg and webp, and the bytes must start
 * with the declared format's magic number). Every failure is a 400. Callers pass their own size
 * limit; ownership, storage and lifecycle stay in each feature's service. Never logs image bytes.
 */
public final class ImageUploadValidator {

  private ImageUploadValidator() {}

  /** Decodes a REST body's base64; 400 when it is empty, invalid or obviously above {@code maxBytes}. */
  public static byte[] decodeBase64(String dataBase64, int maxBytes) {
    if (dataBase64 == null || dataBase64.isBlank()) {
      throw badRequest("dataBase64 must not be empty");
    }
    // 4 base64 characters carry 3 bytes; refuse before allocating the decoded copy.
    if (dataBase64.length() > (long) (maxBytes / 3 + 1) * 4 + 4) {
      throw tooLarge(maxBytes);
    }
    try {
      return Base64.getDecoder().decode(dataBase64);
    } catch (IllegalArgumentException ex) {
      throw badRequest("dataBase64 is not valid base64");
    }
  }

  /**
   * The format of an upload that declares {@code declaredMime}: 400 when the type is not accepted,
   * the data is empty or larger than {@code maxBytes}, or the bytes do not match the declared type.
   */
  public static ImageFormat requireSupportedImage(byte[] data, String declaredMime, int maxBytes) {
    ImageFormat format = ImageFormat.fromMimeType(declaredMime);
    if (format == null) {
      throw badRequest("Only image/png, image/jpeg and image/webp are accepted");
    }
    if (data == null || data.length == 0) {
      throw badRequest("The image is empty");
    }
    if (data.length > maxBytes) {
      throw tooLarge(maxBytes);
    }
    if (!format.matches(data)) {
      throw badRequest("The data is not a valid " + declaredMime + " image");
    }
    return format;
  }

  private static ResponseStatusException tooLarge(int maxBytes) {
    return badRequest("The image is larger than " + maxBytes + " bytes");
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
