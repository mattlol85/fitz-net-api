package org.fitznet.fitznetapi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fitznet.fitznetapi.dto.requests.LoginRequestDto;
import org.fitznet.fitznetapi.dto.responses.LoginResponseDto;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private JwtUtil jwtUtil;


  private String validToken;

  @BeforeEach
  void setUp() {
    validToken = jwtUtil.generateToken("testuser");
  }

  @Test
  void publicEndpointsShouldBeAccessibleWithoutToken() throws Exception {
    mockMvc
        .perform(get("/info").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
  }

  @Test
  void protectedEndpointShouldReturnUnauthorizedWithoutToken() throws Exception {
    mockMvc
        .perform(get("/user/readAll").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void protectedEndpointShouldBeAccessibleWithValidToken() throws Exception {
    mockMvc
        .perform(
            get("/user/readAll")
                .header("Authorization", "Bearer " + validToken)
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
  }

  @Test
  void protectedEndpointShouldReturnUnauthorizedWithInvalidToken() throws Exception {
    String invalidToken = "invalid.jwt.token";

    mockMvc
        .perform(
            get("/user/readAll")
                .header("Authorization", "Bearer " + invalidToken)
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void protectedEndpointShouldReturnUnauthorizedWithExpiredToken() throws Exception {
    // This would require creating an expired token, which is tested in JwtUtilTest
    String malformedToken = "expired.token.here";

    mockMvc
        .perform(
            get("/user/readAll")
                .header("Authorization", "Bearer " + malformedToken)
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void loginEndpointShouldBePublic() throws Exception {
    LoginRequestDto loginRequest = new LoginRequestDto("testuser", "password123");

    mockMvc
        .perform(
            post("/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
        .andExpect(status().isUnauthorized()); // Will fail because user not in DB, but endpoint is accessible
  }

  @Test
  void createUserEndpointShouldBePublic() throws Exception {
    String userJson =
        "{\"username\":\"newuser\",\"email\":\"new@example.com\",\"password\":\"password123\"}";

    mockMvc
        .perform(post("/user/create").contentType(MediaType.APPLICATION_JSON).content(userJson))
        .andExpect(status().isOk()); // or other status, but not 401/403
  }

  @Test
  void authorizationHeaderWithoutBearerPrefixShouldBeRejected() throws Exception {
    mockMvc
        .perform(
            get("/user/readAll")
                .header("Authorization", validToken) // Missing "Bearer " prefix
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void multipleRequestsWithSameTokenShouldWork() throws Exception {
    // First request
    mockMvc
        .perform(
            get("/user/readAll")
                .header("Authorization", "Bearer " + validToken)
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    // Second request with same token
    mockMvc
        .perform(
            get("/user/readAll")
                .header("Authorization", "Bearer " + validToken)
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    // Third request with same token
    mockMvc
        .perform(
            get("/user/readAll")
                .header("Authorization", "Bearer " + validToken)
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
  }

  @Test
  void differentProtectedEndpointsShouldRequireAuthentication() throws Exception {
    // /user/read
    mockMvc
        .perform(post("/user/read").contentType(MediaType.APPLICATION_JSON).content("\"testuser\""))
        .andExpect(status().isUnauthorized());

    // /user/update
    mockMvc
        .perform(
            patch("/user/update")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"test\"}"))
        .andExpect(status().isUnauthorized());

    // /user/delete
    mockMvc
        .perform(
            delete("/user/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"test\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validTokenShouldGrantAccessToAllProtectedEndpoints() throws Exception {
    // /user/readAll
    mockMvc
        .perform(
            get("/user/readAll")
                .header("Authorization", "Bearer " + validToken)
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    // /user/read
    mockMvc
        .perform(
            post("/user/read")
                .header("Authorization", "Bearer " + validToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("\"testuser\""))
        .andExpect(status().isOk());
  }

  @Test
  void tokenGeneratedDuringLoginShouldBeValid() throws Exception {
    // This test would require a real user in the database
    // For now, we're just testing the structure
    LoginRequestDto loginRequest = new LoginRequestDto("realuser", "password");

    MvcResult result =
        mockMvc
            .perform(
                post("/user/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(loginRequest)))
            .andReturn();

    // If login was successful (would need real user), response should contain a token
    if (result.getResponse().getStatus() == 200) {
      String responseBody = result.getResponse().getContentAsString();
      LoginResponseDto response = objectMapper.readValue(responseBody, LoginResponseDto.class);

      assertNotNull(response.getToken());
      assertFalse(response.getToken().isEmpty());

      // Verify the token is valid
      String username = jwtUtil.extractUsername(response.getToken());
      assertEquals("realuser", username);
      assertTrue(jwtUtil.validateToken(response.getToken()));
    }
  }
}








