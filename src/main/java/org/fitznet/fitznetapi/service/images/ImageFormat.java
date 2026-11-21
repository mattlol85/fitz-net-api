package org.fitznet.fitznetapi.service.images;

/**
 * The image formats the API accepts for uploads, with the magic number each file must start with.
 * Shared by every feature that stores user images; each feature keeps its own limits and storage.
 */
public enum ImageFormat {
  PNG("image/png"),
  JPEG("image/jpeg"),
  WEBP("image/webp");

  private final String mimeType;

  ImageFormat(String mimeType) {
    this.mimeType = mimeType;
  }

  public String mimeType() {
    return mimeType;
  }

  /** The format with this exact mime type, or null when it is not accepted. */
  public static ImageFormat fromMimeType(String mimeType) {
    if (mimeType == null) {
      return null;
    }
    for (ImageFormat format : values()) {
      if (format.mimeType.equals(mimeType)) {
        return format;
      }
    }
    return null;
  }

  /** Does the content really start with this format's magic number? */
  public boolean matches(byte[] data) {
    if (data == null) {
      return false;
    }
    return switch (this) {
      case PNG -> startsWith(data, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
      case JPEG -> startsWith(data, 0, 0xFF, 0xD8, 0xFF);
      case WEBP -> startsWith(data, 0, 'R', 'I', 'F', 'F') && startsWith(data, 8, 'W', 'E', 'B', 'P');
    };
  }

  private static boolean startsWith(byte[] data, int offset, int... expected) {
    if (data.length < offset + expected.length) {
      return false;
    }
    for (int i = 0; i < expected.length; i++) {
      if ((data[offset + i] & 0xFF) != expected[i]) {
        return false;
      }
    }
    return true;
  }
}
