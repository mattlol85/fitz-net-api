package org.fitznet.fitznetapi.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.fitznet.fitznetapi.metrics.FitzNetMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EncryptionServiceTest {

  private EncryptionService encryptionService;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() throws Exception {
    KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
    keyGenerator.init(128);
    SecretKey secretKey = keyGenerator.generateKey();
    String encodedKey = Base64.getEncoder().encodeToString(secretKey.getEncoded());

    meterRegistry = new SimpleMeterRegistry();
    encryptionService = new EncryptionService(encodedKey, new FitzNetMetrics(meterRegistry));
  }

  @AfterEach
  void tearDown() {
    meterRegistry.close();
  }

  @Test
  void shouldEncryptDataAndRecordMetric() throws Exception {
    String data = "Hello, World!";

    String encryptedData = encryptionService.encrypt(data);

    assertNotNull(encryptedData);
    assertNotEquals(data, encryptedData);
    assertCounterCount("fitznet.encryption.operations", 1.0, "operation", "encrypt", "result", "success");
    assertTimerCount("fitznet.encryption.operation", 1L, "operation", "encrypt", "result", "success");
  }

  @Test
  void shouldDecryptDataAndRecordMetric() throws Exception {
    String data = "Hello, World!";
    String encryptedData = encryptionService.encrypt(data);

    String decryptedData = encryptionService.decrypt(encryptedData);

    assertEquals(data, decryptedData);
    assertCounterCount("fitznet.encryption.operations", 1.0, "operation", "decrypt", "result", "success");
    assertTimerCount("fitznet.encryption.operation", 1L, "operation", "decrypt", "result", "success");
  }

  @Test
  void shouldHandleEmptyString() throws Exception {
    String data = "";

    String encryptedData = encryptionService.encrypt(data);
    String decryptedData = encryptionService.decrypt(encryptedData);

    assertNotNull(encryptedData);
    assertNotEquals(data, encryptedData);
    assertEquals(data, decryptedData);
  }

  @Test
  void shouldHandleSpecialCharacters() throws Exception {
    String data = "!@#$%^&*()_+-={}[]|:;\"'<>,.?/`~";

    String encryptedData = encryptionService.encrypt(data);
    String decryptedData = encryptionService.decrypt(encryptedData);

    assertNotNull(encryptedData);
    assertNotEquals(data, encryptedData);
    assertEquals(data, decryptedData);
  }

  @Test
  void shouldRecordFailureMetricForInvalidCiphertext() {
    assertThrows(Exception.class, () -> encryptionService.decrypt("not-base64"));
    assertCounterCount("fitznet.encryption.operations", 1.0, "operation", "decrypt", "result", "failure");
    assertTimerCount("fitznet.encryption.operation", 1L, "operation", "decrypt", "result", "failure");
  }

  private void assertCounterCount(String name, double expectedCount, String... tags) {
    Counter counter = meterRegistry.find(name).tags(tags).counter();
    assertNotNull(counter);
    assertEquals(expectedCount, counter.count());
  }

  private void assertTimerCount(String name, long expectedCount, String... tags) {
    Timer timer = meterRegistry.find(name).tags(tags).timer();
    assertNotNull(timer);
    assertEquals(expectedCount, timer.count());
  }
}
