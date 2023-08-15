import com.github.sherter.googlejavaformatgradleplugin.GoogleJavaFormat
import com.github.sherter.googlejavaformatgradleplugin.VerifyGoogleJavaFormat

plugins {
    java
    id("org.springframework.boot") version "3.1.2"
    id("io.spring.dependency-management") version "1.1.2"
    id("com.github.sherter.google-java-format") version "0.9"
}

group = "org.fitznet"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-data-rest")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
googleJavaFormat {
    toolVersion = "1.7"
}

tasks.register<GoogleJavaFormat>("format") {
    source("src/main")
    source("src/test")
    include("**/*.java")
}

tasks.register<VerifyGoogleJavaFormat>("verifyFormatting") {
    source("src/main")
    include("**/*.java")
    ignoreFailures = false
}

tasks.named("verifyFormatting") {
    dependsOn("format")
}

tasks.named("check") {
dependsOn("verifyFormatting")
}

apply(plugin = "com.github.sherter.google-java-format")