package org.fitznet.fitznetapi.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import org.fitznet.fitznetapi.dto.encryption.EncryptRequest;
import org.fitznet.fitznetapi.dto.encryption.EncryptResponse;
import org.fitznet.fitznetapi.service.EncryptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EncryptionController {
  EncryptionService encryptionService;

  @Autowired
  public EncryptionController(EncryptionService encryptionService) {
    this.encryptionService = encryptionService;
  }

  @PostMapping(value = "/encrypt", produces = APPLICATION_JSON_VALUE)
  public EncryptResponse encrypt(@RequestBody EncryptRequest request) {
    try {
      String encryptedData = encryptionService.encrypt(request.getData());
      return new EncryptResponse(encryptedData); // Return the response object
    } catch (Exception e) {
      throw new RuntimeException("Error encrypting data", e);
    }
  }

  @PostMapping(value = "/decrypt", produces = APPLICATION_JSON_VALUE)
  public EncryptResponse decrypt(@RequestBody EncryptRequest request) {
    try {
      String encryptedData = encryptionService.decrypt(request.getData());
      return new EncryptResponse(encryptedData); // Return the response object
    } catch (Exception e) {
      throw new RuntimeException("Error decrypting data", e);
    }
  }
}
