package org.fitznet.fitznetapi.service.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageDimensionsTest {

  static byte[] encode(String format, int width, int height) throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, format, out);
    return out.toByteArray();
  }

  @Test
  void shouldReadPngDimensions() throws IOException {
    assertEquals(
        new ImageDimensions(40, 30), ImageDimensions.read(ImageFormat.PNG, encode("png", 40, 30)));
  }

  @Test
  void shouldReadJpegDimensions() throws IOException {
    assertEquals(
        new ImageDimensions(64, 48), ImageDimensions.read(ImageFormat.JPEG, encode("jpg", 64, 48)));
  }

  @Test
  void shouldReadWebpLossyLosslessAndExtendedHeaders() {
    byte[] lossy = webp("VP8 ", 30);
    lossy[26] = (byte) 0x90; // 400 little-endian
    lossy[27] = 0x01;
    lossy[28] = (byte) 0x2C; // 300
    lossy[29] = 0x01;
    assertEquals(new ImageDimensions(400, 300), ImageDimensions.read(ImageFormat.WEBP, lossy));

    byte[] extended = webp("VP8X", 30);
    extended[24] = (byte) 199; // width - 1
    extended[27] = 99; // height - 1
    assertEquals(new ImageDimensions(200, 100), ImageDimensions.read(ImageFormat.WEBP, extended));

    byte[] lossless = webp("VP8L", 30);
    // width-1 = 9 (14 bits), height-1 = 4 (14 bits) packed little-endian after the 0x2F signature
    int bits = 9 | (4 << 14);
    lossless[21] = (byte) bits;
    lossless[22] = (byte) (bits >> 8);
    lossless[23] = (byte) (bits >> 16);
    lossless[24] = (byte) (bits >> 24);
    assertEquals(new ImageDimensions(10, 5), ImageDimensions.read(ImageFormat.WEBP, lossless));
  }

  @Test
  void shouldReturnNullForTruncatedOrUnknownHeaders() {
    byte[] pngSignatureOnly = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    assertNull(ImageDimensions.read(ImageFormat.PNG, pngSignatureOnly));
    assertNull(ImageDimensions.read(ImageFormat.JPEG, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));
    assertNull(ImageDimensions.read(ImageFormat.WEBP, webp("ABCD", 30)));
  }

  private static byte[] webp(String chunk, int size) {
    byte[] data = new byte[size];
    byte[] riff = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
    System.arraycopy(riff, 0, data, 0, riff.length);
    for (int i = 0; i < 4; i++) {
      data[12 + i] = (byte) chunk.charAt(i);
    }
    return data;
  }
}
