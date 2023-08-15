package org.fitznet.fitznetapi.controller;

import com.google.gson.JsonObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiInfoController {

  @GetMapping(value = "/info", produces = "application/json")
  public JsonObject getApiInfo() {
    JsonObject response = new JsonObject();

    response.addProperty("status", "alive");
    response.addProperty("collectionCount", 0);
    response.addProperty("version", "0.0.1");
    return response;
  }
}
