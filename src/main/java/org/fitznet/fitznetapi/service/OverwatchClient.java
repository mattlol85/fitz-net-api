package org.fitznet.fitznetapi.service;

import java.util.List;
import org.fitznet.fitznetapi.dto.overfast.OverfastPlayerCompleteDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastStatsSummaryResponseDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastSummaryDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSearchResponseDto;
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
    OverwatchPlayerSearchResponseDto response =
        restClient
            .get()
            .uri(b -> b.path("/players").queryParam("name", name).build())
            .retrieve()
            .onStatus(HttpStatusCode::isError, (request, response2) -> {
              throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to search Overwatch players");
            })
            .body(OverwatchPlayerSearchResponseDto.class);
    if (response == null || response.getResults() == null) {
      return List.of();
    }
    return response.getResults();
  }

  /**
   * Resolves a user-provided battletag (e.g. "Zmat-1733") to the internal Blizzard player ID
   * used by the Overfast API for all player-specific endpoints.
   * If the input already looks like an internal ID (contains "%7C" or "|"), it is returned as-is.
   */
  public String resolveInternalPlayerId(String nameOrBattleTag) {
    if (nameOrBattleTag == null || nameOrBattleTag.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Player ID is required");
    }
    // Already an internal Blizzard ID
    if (nameOrBattleTag.contains("%7C") || nameOrBattleTag.contains("|")) {
      return nameOrBattleTag;
    }
    // Extract the name part by stripping trailing tag discriminator (#1234 or -1234)
    String searchName = nameOrBattleTag.replaceAll("[#\\-]\\d{3,6}$", "");
    List<OverwatchPlayerSearchResultDto> results = searchPlayers(searchName);
    if (results.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Overwatch player not found: " + nameOrBattleTag);
    }
    return results.get(0).getPlayerId();
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

  /**
   * Get all player data (summary + career stats). Uses the /players/{player_id} endpoint per the
   * Overfast API spec (v4+). The former /players/{player_id}/complete no longer exists.
   */
  public OverfastPlayerCompleteDto getCompletePlayerInfo(String playerId) {
    try {
      return restClient
          .get()
          .uri("/players/{playerId}", playerId)
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
        summary.setUsername(summaryDto.getUsername());
        summary.setAvatar(summaryDto.getAvatar());
        OverfastPlayerCompleteDto fallback = new OverfastPlayerCompleteDto();
        fallback.setSummary(summary);
        return fallback;
      }
      throw ex;
    }
  }
}
