package org.fitznet.fitznetapi.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

import org.fitznet.fitznetapi.dto.encryption.EncryptRequest;
import org.fitznet.fitznetapi.dto.encryption.EncryptResponse;
import org.fitznet.fitznetapi.service.EncryptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

public class EncryptionControllerTest {

  @Mock private EncryptionService encryptionService;

  @InjectMocks private EncryptionController encryptionController;

  private AutoCloseable mocks;
  private final String testData = "Hello, World!";
  private final String encryptedData = "EncryptedData";
  private final String decryptedData = "Hello, World!";

  @BeforeEach
  public void setUp() throws Exception {
    mocks = openMocks(this);
    when(encryptionService.encrypt(testData)).thenReturn(encryptedData);
    when(encryptionService.decrypt(encryptedData)).thenReturn(decryptedData);
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }

  @Test
  public void testEncrypt() throws Exception {
    EncryptRequest request = new EncryptRequest(testData);

    EncryptResponse response = encryptionController.encrypt(request);

    assertEquals(encryptedData, response.getData());

    verify(encryptionService, times(1)).encrypt(testData);
  }

  @Test
  public void testDecrypt() throws Exception {
    EncryptRequest request = new EncryptRequest(encryptedData);

    EncryptResponse response = encryptionController.decrypt(request);

    assertEquals(decryptedData, response.getData());

    verify(encryptionService, times(1)).decrypt(encryptedData);
  }
}
