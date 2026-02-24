package org.fitznet.fitznetapi.config;

import de.flapdoodle.embed.mongo.distribution.Version;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class EmbeddedMongoTestConfiguration {

    @Bean
    public Version.Main version() {
        // Use MongoDB 4.4 which has better platform support
        return Version.Main.V4_4;
    }
}

