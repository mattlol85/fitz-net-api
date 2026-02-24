package org.fitznet.fitznetapi;

import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Import(EmbeddedMongoTestConfiguration.class)
class FitzNetApiApplicationTests {

  @MockitoBean
  private UserRepository userRepository;

  @Test
  void contextLoads() {}
}
