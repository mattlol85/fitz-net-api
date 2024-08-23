package org.fitznet.fitznetapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication
@EnableMongoRepositories
public class FitzNetApiApplication {

  public static void main(String[] args) {
    Logger log = LoggerFactory.getLogger(FitzNetApiApplication.class);
    SpringApplication.run(FitzNetApiApplication.class, args);
    log.info("Initialized.");
  }
}
