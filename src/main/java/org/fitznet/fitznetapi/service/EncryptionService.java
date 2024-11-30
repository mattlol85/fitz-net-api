package org.fitznet.fitznetapi.service;

import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EncryptionService {

  private final SecretKey secretKey;

  public EncryptionService(@Value("${encryption.key}") String base64EncodedKey) {
    // Decode key
    byte[] decodedKey = Base64.getDecoder().decode(base64EncodedKey);
    this.secretKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
  }

  public String encrypt(String data) throws Exception {
    Cipher cipher = Cipher.getInstance("AES");
    cipher.init(Cipher.ENCRYPT_MODE, secretKey);
    byte[] encryptedBytes = cipher.doFinal(data.getBytes());
    return Base64.getEncoder().encodeToString(encryptedBytes);
  }

  public String decrypt(String encryptedData) throws Exception {
    Cipher cipher = Cipher.getInstance("AES");
    cipher.init(Cipher.DECRYPT_MODE, secretKey);
    byte[] decodedBytes = Base64.getDecoder().decode(encryptedData);
    byte[] decryptedBytes = cipher.doFinal(decodedBytes);
    return new String(decryptedBytes);
  }
}
