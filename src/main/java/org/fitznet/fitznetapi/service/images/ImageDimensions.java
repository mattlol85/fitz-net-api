package org.fitznet.fitznetapi.service.images;

/**
 * The pixel size of an image, read from its header without decoding it. {@link #read} returns null
 * when the header is truncated or not understood; callers treat the size as unknown.
 */
public record ImageDimensions(int width, int height) {

  public static ImageDimensions read(ImageFormat format, byte[] data) {
    if (format == null || data == null) {
      return null;
    }
    try {
      ImageDimensions dims =
          switch (format) {
            case PNG -> png(data);
            case JPEG -> jpeg(data);
            case WEBP -> webp(data);
          };
      return dims != null && dims.width > 0 && dims.height > 0 ? dims : null;
    } catch (ArrayIndexOutOfBoundsException ex) {
      return null;
    }
  }

  /** The IHDR chunk always comes first: width and height are big-endian at offsets 16 and 20. */
  private static ImageDimensions png(byte[] d) {
    if (d.length < 24 || d[12] != 'I' || d[13] != 'H' || d[14] != 'D' || d[15] != 'R') {
      return null;
    }
    return new ImageDimensions(be32(d, 16), be32(d, 20));
  }

  /** Walks the marker segments up to the first start-of-frame (SOF0..SOF15 except DHT/JPG/DAC). */
  private static ImageDimensions jpeg(byte[] d) {
    int i = 2;
    while (i + 3 < d.length) {
      if ((d[i] & 0xFF) != 0xFF) {
        return null;
      }
      int marker = d[i + 1] & 0xFF;
      if (marker == 0xFF) {
        i++;
        continue;
      }
      if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
        i += 2;
        continue;
      }
      if (marker == 0xD9 || marker == 0xDA) {
        return null;
      }
      int length = be16(d, i + 2);
      boolean startOfFrame =
          marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
      if (startOfFrame) {
        return i + 8 < d.length ? new ImageDimensions(be16(d, i + 7), be16(d, i + 5)) : null;
      }
      if (length < 2) {
        return null;
      }
      i += 2 + length;
    }
    return null;
  }

  /** The first chunk after "WEBP" is VP8 (lossy), VP8L (lossless) or VP8X (extended). */
  private static ImageDimensions webp(byte[] d) {
    if (d.length < 30 || d[12] != 'V' || d[13] != 'P' || d[14] != '8') {
      return null;
    }
    return switch (d[15]) {
      case ' ' -> new ImageDimensions(le16(d, 26) & 0x3FFF, le16(d, 28) & 0x3FFF);
      case 'L' -> {
        int b0 = d[21] & 0xFF;
        int b1 = d[22] & 0xFF;
        int b2 = d[23] & 0xFF;
        int b3 = d[24] & 0xFF;
        yield new ImageDimensions(
            1 + (((b1 & 0x3F) << 8) | b0), 1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6)));
      }
      case 'X' -> new ImageDimensions(1 + le24(d, 24), 1 + le24(d, 27));
      default -> null;
    };
  }

  private static int be16(byte[] d, int i) {
    return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
  }

  private static int be32(byte[] d, int i) {
    return ((d[i] & 0xFF) << 24) | ((d[i + 1] & 0xFF) << 16) | ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
  }

  private static int le16(byte[] d, int i) {
    return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
  }

  private static int le24(byte[] d, int i) {
    return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) | ((d[i + 2] & 0xFF) << 16);
  }
}
