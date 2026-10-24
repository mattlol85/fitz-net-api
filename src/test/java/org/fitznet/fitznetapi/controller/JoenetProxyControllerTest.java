package org.fitznet.fitznetapi.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.dto.UserDTO;
import org.fitznet.fitznetapi.dto.requests.LoginRequestDto;
import org.fitznet.fitznetapi.model.PermissionDefinition;
import org.fitznet.fitznetapi.repository.PermissionRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.JoenetProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs the real security chain (JWT filter, method security, exception handler) with the proxy
 * service mocked, so it checks who gets in and how the controller maps the request onto the
 * service. Upstream behavior itself is covered by {@code JoenetProxyServiceTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EmbeddedMongoTestConfiguration.class)
class JoenetProxyControllerTest {

  private static final String ADMIN_KEY_HEADER = "X-Admin-Key";
  private static final String ADMIN_KEY_VALUE = "test-admin-api-key";
  private static final String JOENET_KEY = "pasted-radarr-key";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private PermissionRepository permissionRepository;

  @MockitoBean private JoenetProxyService joenetProxyService;

  @BeforeEach
  void setUp() {
    userRepository.deleteAll();
    permissionRepository.deleteAll();
    permissionRepository.save(PermissionDefinition.builder().name("JOENET_USER").build());
    permissionRepository.save(PermissionDefinition.builder().name("RADARR").build());
  }

  private String tokenFor(String username, String... permissions) throws Exception {
    mockMvc
        .perform(
            post("/user/create")
                .contentType(APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new UserDTO(username, username + "@example.com", "password123"))))
        .andExpect(status().isOk());
    for (String permission : permissions) {
      mockMvc
          .perform(
              post("/admin/users/" + username + "/permissions/" + permission)
                  .header(ADMIN_KEY_HEADER, ADMIN_KEY_VALUE))
          .andExpect(status().isOk());
    }
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
    return "Bearer " + json.get("token").asText();
  }

  private void upstreamReturns(HttpStatus status, String body) {
    when(joenetProxyService.forward(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8)));
  }

  // ---- authorization ----

  @Test
  void shouldRejectUnauthenticatedWith401() throws Exception {
    mockMvc
        .perform(get("/joenet/radarr/system/status").header("X-Joenet-Key", JOENET_KEY))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(joenetProxyService);
  }

  @Test
  void shouldRejectUserWithoutJoenetPermissionWith403() throws Exception {
    String token = tokenFor("bob", "RADARR");

    mockMvc
        .perform(
            get("/joenet/radarr/system/status")
                .header("Authorization", token)
                .header("X-Joenet-Key", JOENET_KEY))
        .andExpect(status().isForbidden());

    verifyNoInteractions(joenetProxyService);
  }

  @Test
  void shouldRejectUserWithNoPermissionsWith403() throws Exception {
    String token = tokenFor("nobody");

    mockMvc
        .perform(
            post("/joenet/sonarr/command")
                .header("Authorization", token)
                .header("X-Joenet-Key", JOENET_KEY)
                .contentType(APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(joenetProxyService);
  }

  @Test
  void shouldAllowJoenetUser() throws Exception {
    String token = tokenFor("joe", "JOENET_USER");
    upstreamReturns(HttpStatus.OK, "{\"version\":\"5.0\"}");

    mockMvc
        .perform(
            get("/joenet/radarr/system/status")
                .header("Authorization", token)
                .header("X-Joenet-Key", JOENET_KEY))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/json"))
        .andExpect(content().string("{\"version\":\"5.0\"}"));
  }

  @Test
  void shouldAllowAdmin() throws Exception {
    String token = tokenFor("root", "ADMIN");
    upstreamReturns(HttpStatus.OK, "[]");

    mockMvc
        .perform(
            get("/joenet/sonarr/series")
                .header("Authorization", token)
                .header("X-Joenet-Key", JOENET_KEY))
        .andExpect(status().isOk())
        .andExpect(content().string("[]"));
  }

  // ---- mapping onto the service ----

  @Test
  void shouldPassAppPathQueryKeyAndMethodToService() throws Exception {
    String token = tokenFor("joe", "JOENET_USER");
    upstreamReturns(HttpStatus.OK, "[]");

    mockMvc
        .perform(
            get("/joenet/radarr/movie/lookup")
                .queryParam("term", "the matrix")
                .queryParam("page", "2")
                .header("Authorization", token)
                .header("X-Joenet-Key", JOENET_KEY)
                .accept(APPLICATION_JSON))
        .andExpect(status().isOk());

    verify(joenetProxyService)
        .forward(
            eq("radarr"),
            eq("/movie/lookup"),
            eq("term=the%20matrix&page=2"),
            eq(HttpMethod.GET),
            eq(JOENET_KEY),
            isNull(),
            eq("application/json"),
            isNull());
  }

  @Test
  void shouldNotForwardTheJwtToTheService() throws Exception {
    String token = tokenFor("joe", "JOENET_USER");
    upstreamReturns(HttpStatus.OK, "{}");

    mockMvc
        .perform(
            get("/joenet/radarr/system/status")
                .header("Authorization", token)
                .header("X-Joenet-Key", JOENET_KEY))
        .andExpect(status().isOk());

    ArgumentCaptor<String> strings = ArgumentCaptor.forClass(String.class);
    verify(joenetProxyService)
        .forward(any(), any(), any(), any(), strings.capture(), any(), any(), any());
    assertEquals(JOENET_KEY, strings.getValue());
  }

  @Test
  void shouldForwardPostBodyAndContentType() throws Exception {
    String token = tokenFor("joe", "JOENET_USER");
    upstreamReturns(HttpStatus.CREATED, "{\"id\":1}");
    String json = "{\"name\":\"RefreshMovie\"}";

    mockMvc
        .perform(
            post("/joenet/radarr/command")
                .header("Authorization", token)
                .header("X-Joenet-Key", JOENET_KEY)
                .contentType(APPLICATION_JSON)
                .content(json))
        .andExpect(status().isCreated())
        .andExpect(content().string("{\"id\":1}"));

    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(joenetProxyService)
        .forward(
            eq("radarr"),
            eq("/command"),
            isNull(),
            eq(HttpMethod.POST),
            eq(JOENET_KEY),
            argThat((String contentType) -> contentType.startsWith("application/json")),
            any(),
            body.capture());
    assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), body.getValue());
  }

  @Test
  void shouldRoutePutAndDelete() throws Exception {
    String token = tokenFor("joe", "JOENET_USER");
    upstreamReturns(HttpStatus.OK, "{}");

    mockMvc
        .perform(
            put("/joenet/radarr/movie/7")
                .header("Authorization", token)
                .header("X-Joenet-Key", JOENET_KEY)
                .contentType(APPLICATION_JSON)
                .content("{\"monitored\":false}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            delete("/joenet/sonarr/queue/3")
                .queryParam("blocklist", "true")
                .header("Authorization", token)
                .header("X-Joenet-Key", JOENET_KEY))
        .andExpect(status().isOk());

    verify(joenetProxyService)
        .forward(
            eq("radarr"), eq("/movie/7"), isNull(), eq(HttpMethod.PUT), any(), any(), any(), any());
    verify(joenetProxyService)
        .forward(
            eq("sonarr"),
            eq("/queue/3"),
            eq("blocklist=true"),
            eq(HttpMethod.DELETE),
            any(),
            any(),
            any(),
            any());
  }

  // ---- status mapping ----

  @Test
  void shouldPassUpstreamUnauthorizedThroughAs401() throws Exception {
    String token = tokenFor("joe", "JOENET_USER");
    upstreamReturns(HttpStatus.UNAUTHORIZED, "{\"error\":\"Unauthorized\"}");

    mockMvc
        .perform(
            get("/joenet/radarr/system/status")
                .header("Authorization", token)
                .header("X-Joenet-Key", "wrong"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().string("{\"error\":\"Unauthorized\"}"));
  }

  @Test
  void shouldMapServiceFailuresToEnvelope() throws Exception {
    String token = tokenFor("joe", "JOENET_USER");
    expectFailure(token, HttpStatus.BAD_GATEWAY, "Could not reach radarr", 502);
    expectFailure(token, HttpStatus.SERVICE_UNAVAILABLE, "Joenet is not configured", 503);
    expectFailure(token, HttpStatus.BAD_REQUEST, "Missing X-Joenet-Key header", 400);
    expectFailure(token, HttpStatus.NOT_FOUND, "Unknown Joenet app: radarr", 404);
  }

  private void expectFailure(String token, HttpStatus status, String reason, int code)
      throws Exception {
    doThrow(new ResponseStatusException(status, reason))
        .when(joenetProxyService)
        .forward(any(), any(), any(), any(), any(), any(), any(), any());

    mockMvc
        .perform(get("/joenet/radarr/system/status").header("Authorization", token))
        .andExpect(status().is(code))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value(reason))
        .andExpect(jsonPath("$.status").value(code));
  }

  // ---- path handling ----

  @Test
  void shouldCaptureNestedPathsWithSlashes() throws Exception {
    String token = tokenFor("joe", "JOENET_USER");
    upstreamReturns(HttpStatus.OK, "[]");

    mockMvc
        .perform(
            get("/joenet/radarr/wanted/missing/page/2")
                .header("Authorization", token)
                .header("X-Joenet-Key", JOENET_KEY))
        .andExpect(status().isOk());

    verify(joenetProxyService)
        .forward(
            eq("radarr"),
            eq("/wanted/missing/page/2"),
            isNull(),
            eq(HttpMethod.GET),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void shouldRefuseEncodedTraversalBeforeItReachesTheService() throws Exception {
    String token = tokenFor("joe", "JOENET_USER");

    for (String path :
        new String[] {
          "/joenet/radarr/%2e%2e/admin",
          "/joenet/radarr/movie%2f..%2fadmin",
          "/joenet/radarr/movie%5cadmin",
          "/joenet/radarr/..%2fadmin"
        }) {
      // Spring Security's StrictHttpFirewall rejects these (a 400 in the servlet container); in
      // MockMvc the rejection surfaces as an exception instead of a response.
      try {
        int status =
            mockMvc
                .perform(
                    get(java.net.URI.create(path))
                        .header("Authorization", token)
                        .header("X-Joenet-Key", JOENET_KEY))
                .andReturn()
                .getResponse()
                .getStatus();
        assertEquals(400, status, path);
      } catch (Exception ex) {
        assertEquals(
            "org.springframework.security.web.firewall.RequestRejectedException",
            rootOf(ex).getClass().getName(),
            path);
      }
    }

    verify(joenetProxyService, never())
        .forward(any(), any(), any(), any(), any(), any(), any(), any());
  }

  private static Throwable rootOf(Throwable t) {
    while (t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }
}
