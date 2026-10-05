package org.fitznet.fitznetapi.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.dto.UserDTO;
import org.fitznet.fitznetapi.dto.requests.LoginRequestDto;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(EmbeddedMongoTestConfiguration.class)
class PermissionIntegrationTest {

  private static final String KEY = "X-Admin-Key";
  private static final String KEY_VALUE = "test-admin-api-key";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private org.fitznet.fitznetapi.repository.PermissionRepository permissionRepository;

  @BeforeEach
  void setUp() {
    userRepository.deleteAll();
    permissionRepository.deleteAll();
    definePermission("RADARR");
    definePermission("SONARR");
  }

  private void definePermission(String name) {
    permissionRepository.save(
        org.fitznet.fitznetapi.model.PermissionDefinition.builder().name(name).build());
  }

  private void createUser(String username) throws Exception {
    mockMvc
        .perform(
            post("/user/create")
                .contentType(APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new UserDTO(username, username + "@example.com", "password123"))))
        .andExpect(status().isOk());
  }

  private String login(String username) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/user/login")
                    .contentType(APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new LoginRequestDto(username, "password123"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode json = objectMapper.readTree(body);
    return json.get("token").asText();
  }

  @Test
  void apiKeyCanGrantAndRevokePermissions() throws Exception {
    createUser("alice");

    mockMvc
        .perform(post("/admin/users/alice/permissions/RADARR").header(KEY, KEY_VALUE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions[0]").value("RADARR"));

    // New login reflects permission in response, and /user/me reads from the DB
    String token = login("alice");
    mockMvc
        .perform(get("/user/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions[0]").value("RADARR"));

    mockMvc
        .perform(delete("/admin/users/alice/permissions/RADARR").header(KEY, KEY_VALUE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions").isEmpty());

    // Revocation is immediate even though the old token still carries the claim
    mockMvc
        .perform(get("/user/me").header("Authorization", "Bearer " + token))
        .andExpect(jsonPath("$.permissions").isEmpty());
  }

  @Test
  void putReplacesPermissionSet() throws Exception {
    createUser("alice");
    mockMvc
        .perform(
            put("/admin/users/alice/permissions")
                .header(KEY, KEY_VALUE)
                .contentType(APPLICATION_JSON)
                .content("{\"permissions\":[\"RADARR\",\"SONARR\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions.length()").value(2));
  }

  @Test
  void wrongOrMissingApiKeyIsRejected() throws Exception {
    mockMvc.perform(get("/admin/users")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/admin/users").header(KEY, "nope")).andExpect(status().isUnauthorized());
  }

  @Test
  void regularUserGetsForbiddenOnAdminEndpoints() throws Exception {
    createUser("bob");
    String token = login("bob");
    mockMvc
        .perform(get("/admin/users").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/user/readAll").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminUserCanManageButNotSelfDemote() throws Exception {
    createUser("root");
    createUser("carol");
    mockMvc
        .perform(post("/admin/users/root/permissions/ADMIN").header(KEY, KEY_VALUE))
        .andExpect(status().isOk());
    String token = login("root");

    mockMvc
        .perform(
            post("/admin/users/carol/permissions/SONARR")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            delete("/admin/users/root/permissions/ADMIN")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void listIncludesBuiltInAdminAndDefinedPermissions() throws Exception {
    mockMvc
        .perform(get("/admin/permissions").header(KEY, KEY_VALUE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name=='ADMIN')]").exists())
        .andExpect(jsonPath("$[?(@.name=='RADARR')]").exists());
  }

  @Test
  void permissionsCanBeCreatedAndUsedAtRuntime() throws Exception {
    createUser("alice");
    mockMvc
        .perform(
            post("/admin/permissions")
                .header(KEY, KEY_VALUE)
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"LIVEBOARD\",\"description\":\"The board\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("LIVEBOARD"));
    mockMvc
        .perform(post("/admin/users/alice/permissions/LIVEBOARD").header(KEY, KEY_VALUE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions[0]").value("LIVEBOARD"));
  }

  @Test
  void createRejectsDuplicatesAndBadNames() throws Exception {
    mockMvc
        .perform(
            post("/admin/permissions")
                .header(KEY, KEY_VALUE)
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"RADARR\"}"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            post("/admin/permissions")
                .header(KEY, KEY_VALUE)
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"bad name\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deletingPermissionRevokesItFromUsers() throws Exception {
    createUser("alice");
    mockMvc
        .perform(post("/admin/users/alice/permissions/RADARR").header(KEY, KEY_VALUE))
        .andExpect(status().isOk());
    mockMvc
        .perform(delete("/admin/permissions/RADARR").header(KEY, KEY_VALUE))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/admin/users/alice/permissions").header(KEY, KEY_VALUE))
        .andExpect(jsonPath("$.permissions").isEmpty());
    mockMvc
        .perform(delete("/admin/permissions/RADARR").header(KEY, KEY_VALUE))
        .andExpect(status().isNotFound());
  }

  @Test
  void adminPermissionCannotBeDeleted() throws Exception {
    mockMvc
        .perform(delete("/admin/permissions/ADMIN").header(KEY, KEY_VALUE))
        .andExpect(status().isBadRequest());
  }

  @Test
  void putWithUndefinedPermissionIsBadRequest() throws Exception {
    createUser("alice");
    mockMvc
        .perform(
            put("/admin/users/alice/permissions")
                .header(KEY, KEY_VALUE)
                .contentType(APPLICATION_JSON)
                .content("{\"permissions\":[\"NOPE\"]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownUserReturnsNotFoundAndUnknownPermissionIsBadRequest() throws Exception {
    mockMvc
        .perform(post("/admin/users/ghost/permissions/RADARR").header(KEY, KEY_VALUE))
        .andExpect(status().isNotFound());
    createUser("alice");
    mockMvc
        .perform(post("/admin/users/alice/permissions/BOGUS").header(KEY, KEY_VALUE))
        .andExpect(status().isBadRequest());
  }

  @Test
  void legacyUserWithoutPermissionsFieldLoadsAsEmpty() throws Exception {
    createUser("alice");
    var user = userRepository.findByUsername("alice");
    user.setPermissions(null);
    userRepository.save(user);
    String token = login("alice");
    mockMvc
        .perform(get("/user/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions").isEmpty());
    assertTrue(true);
  }
}
