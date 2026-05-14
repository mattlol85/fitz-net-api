package org.fitznet.fitznetapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.List;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSearchResultDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSummaryDto;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
public class OverwatchClient {

  private static final String OVERFAST_BASE_URL = "https://overfast-api.tekrop.fr";

  private final RestClient restClient;

  public OverwatchClient(RestClient.Builder restClientBuilder) {
    this.restClient = restClientBuilder.baseUrl(OVERFAST_BASE_URL).build();
  }

  public List<OverwatchPlayerSearchResultDto> searchPlayers(String name) {
    OverwatchPlayerSearchResultDto[] players =
        restClient
            .get()
            .uri(uriBuilder -> uriBuilder.path("/players").queryParam("name", name).build())
            .retrieve()
            .onStatus(HttpStatusCode::isError, (request, response) -> {
              throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to search Overwatch players");
            })
            .body(OverwatchPlayerSearchResultDto[].class);

    return players == null ? List.of() : Arrays.asList(players);
  }

  public OverwatchPlayerSummaryDto getPlayerSummary(String playerId) {
    OverwatchPlayerSummaryDto summary =
        restClient
            .get()
            .uri("/players/{playerId}/summary", playerId)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
              throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Overwatch player not found");
            })
            .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
              throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to validate Overwatch player");
            })
            .body(OverwatchPlayerSummaryDto.class);

    if (summary == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Overwatch player not found");
    }

    return summary;
  }

  public JsonNode getStatsSummary(String playerId, String gamemode, String platform) {
    return restClient
        .get()
        .uri(
            uriBuilder -> {
              var builder = uriBuilder.path("/players/{playerId}/stats/summary");
              if (gamemode != null && !gamemode.isBlank()) {
                builder.queryParam("gamemode", gamemode);
              }
              if (platform != null && !platform.isBlank()) {
                builder.queryParam("platform", platform);
              }
              return builder.build(playerId);
            })
        .retrieve()
        .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Overwatch stats not found");
        })
        .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
          throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to fetch Overwatch stats");
        })
        .body(JsonNode.class);
  }
}
