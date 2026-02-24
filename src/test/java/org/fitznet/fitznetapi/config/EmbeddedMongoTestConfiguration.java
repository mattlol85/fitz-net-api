package org.fitznet.fitznetapi.config;

import de.flapdoodle.os.CommonOS;
import de.flapdoodle.os.ImmutablePlatform;
import de.flapdoodle.os.Platform;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class EmbeddedMongoTestConfiguration {

    @Bean
    public Platform platform() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();

        // M1 Mac (ARM64)
        if (osName.contains("mac") && osArch.contains("aarch64")) {
            return ImmutablePlatform.builder()
                .operatingSystem(CommonOS.OS_X)
                .architecture(de.flapdoodle.os.CommonArchitecture.ARM_64)
                .build();
        }

        // Generic Linux x86_64 (for CI and other platforms)
        return ImmutablePlatform.builder()
            .operatingSystem(CommonOS.Linux)
            .architecture(de.flapdoodle.os.CommonArchitecture.X86_64)
            .build();
    }
}

