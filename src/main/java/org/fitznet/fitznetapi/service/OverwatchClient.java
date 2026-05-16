package org.fitznet.fitznetapi.service;

import java.util.Arrays;
import java.util.List;
import org.fitznet.fitznetapi.dto.overfast.OverfastPlayerCompleteDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastStatsSummaryResponseDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastSummaryDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSearchResultDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSummaryDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

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
            .uri(b -> b.path("/players").queryParam("name", name).build())
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

  public OverfastStatsSummaryResponseDto getStatsSummary(String playerId, String gamemode, String platform) {
    return restClient
        .get()
        .uri(b -> {
          var builder = b.path("/players/{playerId}/stats/summary");
          if (gamemode != null && !gamemode.isBlank()) builder.queryParam("gamemode", gamemode);
          if (platform != null && !platform.isBlank()) builder.queryParam("platform", platform);
          return builder.build(playerId);
        })
        .retrieve()
        .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Overwatch stats not found");
        })
        .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
          throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to fetch Overwatch stats");
        })
        .body(OverfastStatsSummaryResponseDto.class);
  }

  public OverfastPlayerCompleteDto getCompletePlayerInfo(String playerId) {
    try {
      return restClient
          .get()
          .uri("/players/{playerId}/complete", playerId)
          .retrieve()
          .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Overwatch player not found");
          })
          .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to fetch Overwatch player data");
          })
          .body(OverfastPlayerCompleteDto.class);
    } catch (ResponseStatusException ex) {
      if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
        // Fallback: build a minimal complete DTO from the summary endpoint
        OverwatchPlayerSummaryDto summaryDto = getPlayerSummary(playerId);
        OverfastSummaryDto summary = new OverfastSummaryDto();
        summary.setUsername(summaryDto.getName());
        summary.setAvatar(summaryDto.getAvatar());
        OverfastPlayerCompleteDto fallback = new OverfastPlayerCompleteDto();
        fallback.setSummary(summary);
        return fallback;
      }
      throw ex;
    }
  }
}
