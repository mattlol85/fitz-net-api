package org.fitznet.fitznetapi.controller;

import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiInfoController {

  @GetMapping(value = "/info", produces = "application/json")
  public Map<String, Object> getApiInfo() {
    Map<String, Object> response = new HashMap<>();
    response.put("status", "alive");
    response.put("collectionCount", 0);
    response.put("version", "0.0.1");
    return response;
  }
}
