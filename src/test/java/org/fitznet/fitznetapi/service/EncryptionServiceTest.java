package org.fitznet.fitznetapi.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EncryptionServiceTest {

  private EncryptionService encryptionService;

  @BeforeEach
  public void setUp() throws Exception {
    // Generate a secret key for testing
    KeyGenerator keyGen = KeyGenerator.getInstance("AES");
    keyGen.init(128);
    SecretKey secretKey = keyGen.generateKey();
    String base64EncodedKey = Base64.getEncoder().encodeToString(secretKey.getEncoded());

    encryptionService = new EncryptionService(base64EncodedKey);
  }

  @Test
  public void testEncryptAndDecrypt() throws Exception {
    String originalData = "Hello, World!";

    String encryptedData = encryptionService.encrypt(originalData);

    assertNotNull(encryptedData);
    assertNotEquals(originalData, encryptedData);

    String decryptedData = encryptionService.decrypt(encryptedData);
    assertEquals(originalData, decryptedData);
  }

  @Test
  public void testEncryptionWithEmptyString() throws Exception {
    String originalData = "";

    String encryptedData = encryptionService.encrypt(originalData);

    assertNotNull(encryptedData);

    String decryptedData = encryptionService.decrypt(encryptedData);

    assertEquals(originalData, decryptedData);
  }

  @Test
  public void testEncryptionWithSpecialCharacters() throws Exception {
    String originalData = "!@#$%^&*()_+{}|:\"<>?-=[]\\;',./";

    String encryptedData = encryptionService.encrypt(originalData);

    assertNotNull(encryptedData);
    assertNotEquals(originalData, encryptedData);

    String decryptedData = encryptionService.decrypt(encryptedData);

    assertEquals(originalData, decryptedData);
  }
}
