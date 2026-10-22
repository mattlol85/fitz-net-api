package org.fitznet.fitznetapi.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

/**
 * Forwards requests to Radarr/Sonarr's {@code /api/v3} on the fixed, configured Joenet host. The
 * host never comes from the caller (no SSRF), only {@code /api/v3/} is reachable, and the caller's
 * API key is passed through per request and never stored or logged.
 */
@Service
public class JoenetProxyService {

  private static final Logger logger = LoggerFactory.getLogger(JoenetProxyService.class);

  static final String API_KEY_HEADER = "X-Api-Key";

  /** Hop-by-hop headers (RFC 9110 7.6.1) plus ones the servlet container recomputes or must not relay. */
  private static final Set<String> DROPPED_RESPONSE_HEADERS =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade",
          "content-length",
          "set-cookie");

  private final RestClient joenetRestClient;
  private final String host;
  private final int radarrPort;
  private final int sonarrPort;

  public JoenetProxyService(
      RestClient joenetRestClient,
      @Value("${joenet.host:}") String host,
      @Value("${joenet.radarr.port:7878}") int radarrPort,
      @Value("${joenet.sonarr.port:8989}") int sonarrPort) {
    this.joenetRestClient = joenetRestClient;
    this.host = host == null ? "" : host.trim();
    this.radarrPort = radarrPort;
    this.sonarrPort = sonarrPort;
  }

  /**
   * @param path the {@code {*path}} value (leading slash allowed), relative to {@code /api/v3/}
   * @param query the raw (still percent-encoded) query string, or null
   * @return the upstream status, body and non-hop-by-hop headers, unchanged
   */
  public ResponseEntity<byte[]> forward(
      String app,
      String path,
      String query,
      HttpMethod method,
      String apiKey,
      String contentType,
      String accept,
      byte[] body) {
    int port = portFor(app);
    if (apiKey == null || apiKey.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing X-Joenet-Key header");
    }
    String encodedPath = encodePath(path);
    if (host.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Joenet is not configured");
    }

    MediaType requestContentType = parseContentType(contentType);
    MediaType[] acceptTypes = parseAccept(accept);
    URI uri = buildUri(port, encodedPath, query);

    logger.debug("Joenet proxy {} {} /{}", method, app, path);
    try {
      RestClient.RequestBodySpec request =
          joenetRestClient
              .method(method)
              .uri(uri)
              .header(API_KEY_HEADER, apiKey)
              .accept(acceptTypes);
      if (body != null && body.length > 0) {
        if (requestContentType != null) {
          request.contentType(requestContentType);
        }
        request.body(body);
      }
      return request.exchange(
          (req, res) -> {
            HttpHeaders headers = new HttpHeaders();
            res.getHeaders()
                .forEach(
                    (name, values) -> {
                      if (!DROPPED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                        headers.addAll(name, values);
                      }
                    });
            return ResponseEntity.status(res.getStatusCode())
                .headers(headers)
                .body(res.getBody().readAllBytes());
          });
    } catch (RestClientException ex) {
      // Deliberately not logging ex.getMessage(): it embeds the full URL, query string included.
      logger.warn(
          "Joenet {} unreachable ({}) for /{}", app, ex.getClass().getSimpleName(), path);
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Could not reach " + app);
    }
  }

  private int portFor(String app) {
    if ("radarr".equals(app)) {
      return radarrPort;
    }
    if ("sonarr".equals(app)) {
      return sonarrPort;
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown Joenet app: " + app);
  }

  /**
   * Validates the caller-supplied path and returns it percent-encoded segment by segment. Rejects
   * anything that could climb out of {@code /api/v3/}: dot segments, backslashes, encoded dots,
   * slashes or backslashes (a decoded value shouldn't still contain them), and control characters.
   */
  private static String encodePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath;
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    String lower = path.toLowerCase(Locale.ROOT);
    if (path.isEmpty()
        || path.contains("..")
        || path.contains("\\")
        || path.contains("//")
        || lower.contains("%2e")
        || lower.contains("%2f")
        || lower.contains("%5c")
        || path.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Joenet path");
    }
    StringBuilder encoded = new StringBuilder();
    for (String segment : path.split("/", -1)) {
      if (".".equals(segment)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Joenet path");
      }
      if (!encoded.isEmpty()) {
        encoded.append('/');
      }
      encoded.append(UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8));
    }
    return encoded.toString();
  }

  private URI buildUri(int port, String encodedPath, String query) {
    // Build the full literal URL rather than substituting URI template variables: RestClient
    // percent-encodes expanded template values (the ":" before the port becomes "%3A"), and would
    // re-encode the already-encoded query string.
    String url = "http://" + host + ":" + port + "/api/v3/" + encodedPath;
    if (query != null && !query.isEmpty()) {
      url += "?" + query;
    }
    try {
      URI uri = URI.create(url);
      if (uri.getHost() == null) {
        logger.error("joenet.host is not a valid host name");
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "Joenet is not configured");
      }
      return uri;
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Joenet query string");
    }
  }

  private static MediaType parseContentType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return null;
    }
    try {
      return MediaType.parseMediaType(contentType);
    } catch (InvalidMediaTypeException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Content-Type");
    }
  }

  private static MediaType[] parseAccept(String accept) {
    if (accept == null || accept.isBlank()) {
      return new MediaType[] {MediaType.ALL};
    }
    try {
      List<MediaType> types = MediaType.parseMediaTypes(accept);
      return types.toArray(new MediaType[0]);
    } catch (InvalidMediaTypeException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Accept header");
    }
  }
}
