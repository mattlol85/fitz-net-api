# Build stage - glibc (not alpine) because the protoc binary that compiles src/main/proto needs it
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .
COPY src src
RUN chmod +x ./gradlew
RUN ./gradlew bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# 8080 HTTP, 9090 gRPC
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "app.jar"]

