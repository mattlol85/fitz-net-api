package org.fitznet.fitznetapi.config;

import de.flapdoodle.embed.mongo.packageresolver.Command;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Configuration for embedded MongoDB in tests.
 * Helps Flapdoodle work across different platforms (macOS, Linux CI, etc.)
 */
@TestConfiguration
public class EmbeddedMongoTestConfiguration {

    @Bean
    public Command command() {
        // Explicitly set the MongoDB command
        return Command.MongoD;
    }
}

