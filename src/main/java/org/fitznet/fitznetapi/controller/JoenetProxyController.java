package org.fitznet.fitznetapi.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.fitznet.fitznetapi.service.JoenetProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JoenetProxyController {

  static final Logger log = LoggerFactory.getLogger(JoenetProxyController.class);

  @Autowired JoenetProxyService joenetProxyService;

  /**
   * Proxies to Radarr/Sonarr {@code /api/v3/{path}}. The caller's {@code X-Joenet-Key} is sent
   * upstream as {@code X-Api-Key}; the JWT {@code Authorization} header is never forwarded. The
   * key is intentionally absent from every log line.
   */
  @PreAuthorize("hasAnyAuthority('PERM_JOENET_USER','PERM_ADMIN')")
  @RequestMapping(
      path = "/joenet/{app}/{*path}",
      method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
  public ResponseEntity<byte[]> proxy(
      @PathVariable String app,
      @PathVariable String path,
      @RequestHeader(value = "X-Joenet-Key", required = false) String apiKey,
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
      @RequestBody(required = false) byte[] body,
      HttpServletRequest request) {
    log.info("Request at {} /joenet/{}", request.getMethod(), app);
    return joenetProxyService.forward(
        app,
        path,
        request.getQueryString(),
        HttpMethod.valueOf(request.getMethod()),
        apiKey,
        contentType,
        accept,
        body);
  }
}
