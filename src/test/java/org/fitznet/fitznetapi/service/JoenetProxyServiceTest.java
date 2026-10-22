package org.fitznet.fitznetapi.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class JoenetProxyServiceTest {

  private static final String KEY = "super-secret-radarr-key";
  private static final String BASE = "http://joenet.lan:";

  private MockRestServiceServer server;
  private JoenetProxyService service;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    service = serviceWithHost("joenet.lan", builder.build());
  }

  private static JoenetProxyService serviceWithHost(String host, RestClient client) {
    return new JoenetProxyService(client, host, 7878, 8989);
  }

  private ResponseEntity<byte[]> get(String app, String path, String query) {
    return service.forward(app, path, query, HttpMethod.GET, KEY, null, null, null);
  }

  private ResponseStatusException rejected(String app, String path) {
    return assertThrows(ResponseStatusException.class, () -> get(app, path, null));
  }

  // ---- URL building / forwarding ----

  @Test
  void shouldForwardToRadarrApiV3OnConfiguredHostAndPort() {
    server
        .expect(requestTo(BASE + "7878/api/v3/system/status"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"version\":\"5\"}", MediaType.APPLICATION_JSON));

    ResponseEntity<byte[]> response = get("radarr", "/system/status", null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("{\"version\":\"5\"}", new String(response.getBody(), StandardCharsets.UTF_8));
    assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    server.verify();
  }

  @Test
  void shouldUseSonarrPortForSonarr() {
    server
        .expect(requestTo(BASE + "8989/api/v3/series"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    assertEquals(HttpStatus.OK, get("sonarr", "/series", null).getStatusCode());
    server.verify();
  }

  @Test
  void shouldSendCallerKeyAsXApiKey() {
    server
        .expect(requestTo(BASE + "7878/api/v3/system/status"))
        .andExpect(header("X-Api-Key", KEY))
        .andExpect(headerDoesNotExist("X-Joenet-Key"))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess());

    get("radarr", "/system/status", null);
    server.verify();
  }

  @Test
  void shouldForwardQueryStringUnchanged() {
    server
        .expect(requestTo(BASE + "7878/api/v3/movie/lookup?term=the%20matrix&page=2"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    get("radarr", "/movie/lookup", "term=the%20matrix&page=2");
    server.verify();
  }

  @Test
  void shouldForwardPostBodyAndContentType() {
    String json = "{\"title\":\"Heat\",\"qualityProfileId\":1}";
    server
        .expect(requestTo(BASE + "7878/api/v3/movie"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Content-Type", "application/json"))
        .andExpect(content().string(json))
        .andRespond(withStatus(HttpStatus.CREATED).body("{\"id\":9}").contentType(MediaType.APPLICATION_JSON));

    ResponseEntity<byte[]> response =
        service.forward(
            "radarr",
            "/movie",
            null,
            HttpMethod.POST,
            KEY,
            "application/json",
            null,
            json.getBytes(StandardCharsets.UTF_8));

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    server.verify();
  }

  @Test
  void shouldForwardPutAndDeleteMethods() {
    server
        .expect(requestTo(BASE + "7878/api/v3/movie/5"))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withSuccess());
    server
        .expect(requestTo(BASE + "7878/api/v3/movie/5?deleteFiles=false"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withStatus(HttpStatus.OK));

    service.forward(
        "radarr", "/movie/5", null, HttpMethod.PUT, KEY, "application/json", null, "{}".getBytes());
    service.forward(
        "radarr", "/movie/5", "deleteFiles=false", HttpMethod.DELETE, KEY, null, null, null);
    server.verify();
  }

  @Test
  void shouldPercentEncodePathSegmentsAndKeepColons() {
    server
        .expect(requestTo(BASE + "7878/api/v3/movie/a%20b/c:d"))
        .andRespond(withSuccess());

    get("radarr", "/movie/a b/c:d", null);
    server.verify();
  }

  // ---- upstream responses ----

  @Test
  void shouldPassUpstreamUnauthorizedThrough() {
    server
        .expect(requestTo(BASE + "7878/api/v3/system/status"))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .body("{\"error\":\"Unauthorized\"}")
                .contentType(MediaType.APPLICATION_JSON));

    ResponseEntity<byte[]> response = get("radarr", "/system/status", null);

    assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    assertEquals(
        "{\"error\":\"Unauthorized\"}", new String(response.getBody(), StandardCharsets.UTF_8));
  }

  @Test
  void shouldPassUpstreamServerErrorAndNotFoundThrough() {
    server
        .expect(requestTo(BASE + "7878/api/v3/movie/1"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));
    server
        .expect(requestTo(BASE + "7878/api/v3/movie/2"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    assertEquals(HttpStatus.NOT_FOUND, get("radarr", "/movie/1", null).getStatusCode());
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, get("radarr", "/movie/2", null).getStatusCode());
  }

  @Test
  void shouldDropHopByHopResponseHeadersButKeepContentType() {
    server
        .expect(requestTo(BASE + "7878/api/v3/system/status"))
        .andRespond(
            withSuccess("{}", MediaType.APPLICATION_JSON)
                .header("Connection", "keep-alive")
                .header("Keep-Alive", "timeout=5")
                .header("Transfer-Encoding", "chunked")
                .header("Set-Cookie", "RadarrAuth=abc")
                .header("X-Application-Version", "5.0"));

    ResponseEntity<byte[]> response = get("radarr", "/system/status", null);

    assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    assertEquals("5.0", response.getHeaders().getFirst("X-Application-Version"));
    assertFalse(response.getHeaders().containsKey("Connection"));
    assertFalse(response.getHeaders().containsKey("Keep-Alive"));
    assertFalse(response.getHeaders().containsKey("Transfer-Encoding"));
    assertFalse(response.getHeaders().containsKey("Set-Cookie"));
  }

  @Test
  void shouldReturnBinaryBodiesIntact() {
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xff};
    server
        .expect(requestTo(BASE + "7878/api/v3/mediacover/1/poster.jpg"))
        .andRespond(withSuccess(png, MediaType.IMAGE_PNG));

    ResponseEntity<byte[]> response = get("radarr", "/mediacover/1/poster.jpg", null);

    assertArrayEquals(png, response.getBody());
    assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
  }

  // ---- failures ----

  @Test
  void shouldReturnBadGatewayOnConnectFailure() {
    server
        .expect(requestTo(BASE + "7878/api/v3/system/status"))
        .andRespond(
            request -> {
              throw new IOException("Connection refused");
            });

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> get("radarr", "/system/status", null));

    assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
  }

  @Test
  void shouldReturnBadGatewayOnReadTimeout() {
    server
        .expect(requestTo(BASE + "7878/api/v3/queue"))
        .andRespond(
            request -> {
              throw new SocketTimeoutException("Read timed out");
            });

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> get("radarr", "/queue", null));

    assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
  }

  @Test
  void shouldReturnServiceUnavailableWhenHostBlank() {
    JoenetProxyService unconfigured = serviceWithHost("", RestClient.create());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> unconfigured.forward("radarr", "/system/status", null, HttpMethod.GET, KEY, null, null, null));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
  }

  @Test
  void shouldReturnServiceUnavailableWhenHostNull() {
    JoenetProxyService unconfigured = serviceWithHost(null, RestClient.create());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> unconfigured.forward("sonarr", "/series", null, HttpMethod.GET, KEY, null, null, null));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
  }

  // ---- validation ----

  @Test
  void shouldRejectUnknownAppWithNotFound() {
    assertEquals(HttpStatus.NOT_FOUND, rejected("lidarr", "/system/status").getStatusCode());
    assertEquals(HttpStatus.NOT_FOUND, rejected("RADARR", "/system/status").getStatusCode());
    assertEquals(HttpStatus.NOT_FOUND, rejected(null, "/system/status").getStatusCode());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   "})
  void shouldRejectMissingKeyWithBadRequest(String key) {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.forward("radarr", "/system/status", null, HttpMethod.GET, key, null, null, null));

    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void shouldRejectNullKeyWithBadRequest() {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.forward("radarr", "/system/status", null, HttpMethod.GET, null, null, null, null));

    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "/",
        "/..",
        "/../admin",
        "/movie/../../../etc/passwd",
        "/movie/..",
        "/./movie",
        "/movie/.",
        "/%2e%2e/admin",
        "/%2E%2E/admin",
        "/movie%2f..%2fadmin",
        "/movie%2Fadmin",
        "/movie%5cadmin",
        "/movie\\admin",
        "\\..\\admin",
        "/movie//1",
        "/movie/1\n",
        "/movie/\u0000"
      })
  void shouldRejectTraversalAndMalformedPaths(String path) {
    ResponseStatusException ex = rejected("radarr", path);

    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    server.verify(); // nothing was sent upstream
  }

  @Test
  void shouldRejectNullPath() {
    assertEquals(HttpStatus.BAD_REQUEST, rejected("radarr", null).getStatusCode());
  }

  @Test
  void shouldRejectQueryStringThatIsNotAValidUri() {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> get("radarr", "/movie", "a=b c\r\nHost: evil"));

    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void shouldRejectInvalidContentType() {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.forward(
                    "radarr", "/movie", null, HttpMethod.POST, KEY, "not a type", null, "x".getBytes()));

    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void shouldNotChangeTargetHostViaPathOrQuery() {
    // An absolute-looking path or an @-style authority must stay inside /api/v3 on the fixed host.
    server
        .expect(requestTo(BASE + "7878/api/v3/http:/evil.example/x?u=http://evil.example"))
        .andRespond(withSuccess());

    get("radarr", "/http:/evil.example/x", "u=http://evil.example");
    server.verify();
  }

  @Test
  void shouldNeverLogTheKey() {
    Logger logger = (Logger) LoggerFactory.getLogger(JoenetProxyService.class);
    Level previous = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.setLevel(Level.TRACE);
    logger.addAppender(appender);
    try {
      server
          .expect(requestTo(BASE + "7878/api/v3/system/status"))
          .andRespond(withSuccess());
      server
          .expect(requestTo(BASE + "7878/api/v3/queue?apikey=" + KEY))
          .andRespond(
              request -> {
                throw new IOException("Connection refused");
              });

      get("radarr", "/system/status", null);
      assertThrows(ResponseStatusException.class, () -> get("radarr", "/queue", "apikey=" + KEY));
      rejected("radarr", "/../x");
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previous);
    }

    assertFalse(appender.list.isEmpty());
    for (ILoggingEvent event : appender.list) {
      assertFalse(event.getFormattedMessage().contains(KEY), event.getFormattedMessage());
      assertNull(event.getThrowableProxy());
    }
  }
}
