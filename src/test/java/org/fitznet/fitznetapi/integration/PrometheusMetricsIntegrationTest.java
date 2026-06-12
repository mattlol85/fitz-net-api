package org.fitznet.fitznetapi.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.dto.UserDTO;
import org.fitznet.fitznetapi.dto.encryption.EncryptRequest;
import org.fitznet.fitznetapi.dto.requests.LoginRequestDto;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(EmbeddedMongoTestConfiguration.class)
class PrometheusMetricsIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;

  @BeforeEach
  void setUp() {
    userRepository.deleteAll();
  }

  @Test
  void shouldExposePrometheusEndpointWithoutAuthentication() throws Exception {
    String body =
        mockMvc
            .perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertTrue(body.contains("jvm_threads_live_threads"));
  }

  @Test
  void shouldExposeCustomMetricsAfterCoreFlows() throws Exception {
    String username = "metricsuser";
    String password = "metrics-password";

    mockMvc
        .perform(
            post("/user/create")
                .contentType(APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new UserDTO(username, username + "@example.com", password))))
        .andExpect(status().isOk());

    MvcResult loginResult =
        mockMvc
            .perform(
                post("/user/login")
                    .contentType(APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(new LoginRequestDto(username, password))))
            .andExpect(status().isOk())
            .andReturn();

    String token =
        objectMapper
            .readTree(loginResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
            .get("token")
            .asText();

    mockMvc
        .perform(
            post("/user/login")
                .contentType(APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new LoginRequestDto(username, "wrong-password"))))
        .andExpect(status().isUnauthorized());

    MvcResult encryptionResult =
        mockMvc
            .perform(
                post("/encrypt")
                    .header("Authorization", "Bearer " + token)
                    .contentType(APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new EncryptRequest("hello metrics"))))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode encryptionBody =
        objectMapper.readTree(encryptionResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
    mockMvc
        .perform(
            post("/decrypt")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new EncryptRequest(encryptionBody.get("data").asText()))))
        .andExpect(status().isOk());

    mockMvc.perform(get("/user/readAll")).andExpect(status().isUnauthorized());

    String body =
        mockMvc
            .perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertMetricLineContains(
        body, "fitznet_user_operations_total", "operation=\"create\"", "result=\"success\"");
    assertMetricLineContains(
        body, "fitznet_user_operations_total", "operation=\"login\"", "result=\"success\"");
    assertMetricLineContains(
        body,
        "fitznet_user_operations_total",
        "operation=\"login\"",
        "result=\"invalid_credentials\"");
    assertMetricLineContains(
        body,
        "fitznet_encryption_operations_total",
        "operation=\"encrypt\"",
        "result=\"success\"");
    assertMetricLineContains(
        body,
        "fitznet_encryption_operations_total",
        "operation=\"decrypt\"",
        "result=\"success\"");
    assertMetricLineContains(
        body, "fitznet_api_failures_total", "type=\"response_status\"", "status=\"401\"");
    assertMetricLineContains(
        body, "fitznet_api_failures_total", "type=\"authentication\"", "status=\"401\"");
  }

  private void assertMetricLineContains(String body, String metricName, String... fragments) {
    String matchingLine =
        Arrays.stream(body.split("\\R"))
            .filter(line -> line.startsWith(metricName + "{") || line.startsWith(metricName + " "))
            .filter(line -> Arrays.stream(fragments).allMatch(line::contains))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected metric line not found for " + metricName));

    String value = matchingLine.substring(matchingLine.lastIndexOf(' ') + 1).trim();
    assertFalse(
        value.equals("0") || value.equals("0.0"),
        () -> "Metric was not incremented: " + matchingLine);
  }
}
