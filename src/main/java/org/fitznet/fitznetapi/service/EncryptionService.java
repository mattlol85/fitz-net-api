package org.fitznet.fitznetapi.service;

import io.micrometer.core.instrument.Timer;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.fitznet.fitznetapi.metrics.FitzNetMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EncryptionService {

  private final SecretKey secretKey;
  private final FitzNetMetrics fitzNetMetrics;

  @Autowired
  public EncryptionService(
      @Value("${encryption.key}") String base64EncodedKey, FitzNetMetrics fitzNetMetrics) {
    byte[] decodedKey = Base64.getDecoder().decode(base64EncodedKey);
    this.secretKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
    this.fitzNetMetrics = fitzNetMetrics;
  }

  public String encrypt(String data) throws Exception {
    Timer.Sample sample = fitzNetMetrics.startSample();
    try {
      Cipher cipher = Cipher.getInstance("AES");
      cipher.init(Cipher.ENCRYPT_MODE, secretKey);
      byte[] encryptedBytes = cipher.doFinal(data.getBytes());
      String encoded = Base64.getEncoder().encodeToString(encryptedBytes);
      fitzNetMetrics.recordEncryptionOperation("encrypt", "success", sample);
      return encoded;
    } catch (Exception ex) {
      fitzNetMetrics.recordEncryptionOperation("encrypt", "failure", sample);
      throw ex;
    }
  }

  public String decrypt(String encryptedData) throws Exception {
    Timer.Sample sample = fitzNetMetrics.startSample();
    try {
      Cipher cipher = Cipher.getInstance("AES");
      cipher.init(Cipher.DECRYPT_MODE, secretKey);
      byte[] decodedBytes = Base64.getDecoder().decode(encryptedData);
      byte[] decryptedBytes = cipher.doFinal(decodedBytes);
      String decrypted = new String(decryptedBytes);
      fitzNetMetrics.recordEncryptionOperation("decrypt", "success", sample);
      return decrypted;
    } catch (Exception ex) {
      fitzNetMetrics.recordEncryptionOperation("decrypt", "failure", sample);
      throw ex;
    }
  }
}
