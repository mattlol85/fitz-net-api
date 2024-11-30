package org.fitznet.fitznetapi.util.encryption;

import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public class KeyGeneratorUtil {

  public static void main(String[] args) throws Exception {
    KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
    keyGenerator.init(256); // AES key size
    SecretKey secretKey = keyGenerator.generateKey();
    String encodedKey = Base64.getEncoder().encodeToString(secretKey.getEncoded());
    System.out.println("Generated Key: " + encodedKey);
  }
}
