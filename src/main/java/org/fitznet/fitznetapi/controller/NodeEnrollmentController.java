package org.fitznet.fitznetapi.controller;

import java.security.Principal;
import org.fitznet.fitznetapi.dto.requests.NodeEnrollmentTokenRequestDto;
import org.fitznet.fitznetapi.dto.responses.NodeEnrollmentTokenResponseDto;
import org.fitznet.fitznetapi.model.NodeEnrollmentToken;
import org.fitznet.fitznetapi.service.NodeEnrollmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NodeEnrollmentController {

  static final Logger log = LoggerFactory.getLogger(NodeEnrollmentController.class);

  @Autowired NodeEnrollmentService nodeEnrollmentService;

  @PostMapping("/node/enrollment-token")
  public NodeEnrollmentTokenResponseDto createEnrollmentToken(
      @RequestBody(required = false) NodeEnrollmentTokenRequestDto request, Principal principal) {
    String label = request != null ? request.getLabel() : null;
    log.info("Request at /node/enrollment-token - user: {}", principal.getName());
    NodeEnrollmentToken token = nodeEnrollmentService.generateToken(principal.getName(), label);
    return new NodeEnrollmentTokenResponseDto(token.getId(), token.getExpiresAt());
  }
}
