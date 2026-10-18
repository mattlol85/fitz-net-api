package org.fitznet.fitznetapi.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class UsernameNormalizerTest {

  @Test
  void shouldLowercaseMixedCase() {
    assertEquals("zmat", UsernameNormalizer.normalize("ZMat"));
  }

  @Test
  void shouldTrimWhitespace() {
    assertEquals("zmat", UsernameNormalizer.normalize("  ZMat \t"));
  }

  @Test
  void shouldReturnNullForNull() {
    assertNull(UsernameNormalizer.normalize(null));
  }
}
