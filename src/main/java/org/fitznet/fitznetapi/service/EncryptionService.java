package org.fitznet.fitznetapi.service;

import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EncryptionService {

  private final SecretKey secretKey;

  public EncryptionService(@Value("${encryption.key}") String base64EncodedKey) {
    // Decode key
    byte[] decodedKey = Base64.getDecoder().decode(base64EncodedKey);
    this.secretKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
  }

  public String encrypt(String data) {
    try {
      Cipher cipher = Cipher.getInstance("AES");
      cipher.init(Cipher.ENCRYPT_MODE, secretKey);
      byte[] encryptedBytes = cipher.doFinal(data.getBytes());
      return Base64.getEncoder().encodeToString(encryptedBytes);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Error encrypting data", e);
    }
  }

  public String decrypt(String encryptedData) {
    try {
      Cipher cipher = Cipher.getInstance("AES");
      cipher.init(Cipher.DECRYPT_MODE, secretKey);
      byte[] decodedBytes = Base64.getDecoder().decode(encryptedData);
      byte[] decryptedBytes = cipher.doFinal(decodedBytes);
      return new String(decryptedBytes);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Error decrypting data", e);
    }
  }
}
