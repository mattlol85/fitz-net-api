package org.fitznet.fitznetapi.service;

import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.dto.overfast.OverfastPlayerCompleteDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastStatsSummaryResponseDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastSummaryDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSearchResponseDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSearchResultDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSummaryDto;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Component
public class OverwatchClient {

  private static final String OVERFAST_BASE_URL = "https://overfast-api.tekrop.fr";

  private final RestClient restClient;

  public OverwatchClient(RestClient.Builder restClientBuilder) {
    this.restClient = restClientBuilder
        .baseUrl(OVERFAST_BASE_URL)
        .requestInterceptor(new OverfastLoggingInterceptor())
        .build();
  }

  // ---- Logging interceptor ----

  private static class OverfastLoggingInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
        ClientHttpRequestExecution execution) throws IOException {
      log.info("[Overfast] --> {} {}", request.getMethod(), request.getURI());
      long start = System.currentTimeMillis();
      ClientHttpResponse response = execution.execute(request, body);
      long elapsed = System.currentTimeMillis() - start;
      log.info("[Overfast] <-- {} {} | status={} | {}ms",
          request.getMethod(), request.getURI(), response.getStatusCode(), elapsed);
      return response;
    }
  }

  // ---- Public methods ----

  public List<OverwatchPlayerSearchResultDto> searchPlayers(String name) {
    log.debug("[Overfast] Searching players with name='{}'", name);
    OverwatchPlayerSearchResponseDto response =
        restClient
            .get()
            .uri(b -> b.path("/players").queryParam("name", name).build())
            .retrieve()
            .onStatus(HttpStatusCode::isError, (request, response2) -> {
              log.error("[Overfast] Player search failed for name='{}' - status={}", name, response2.getStatusCode());
              throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to search Overwatch players");
            })
            .body(OverwatchPlayerSearchResponseDto.class);
    int total = (response != null && response.getResults() != null) ? response.getResults().size() : 0;
    log.info("[Overfast] Player search name='{}' returned {} result(s)", name, total);
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
    // Already an internal Blizzard ID — skip search
    if (nameOrBattleTag.contains("%7C") || nameOrBattleTag.contains("|")) {
      log.info("[Overfast] '{}' is already an internal Blizzard ID — skipping search", nameOrBattleTag);
      return nameOrBattleTag;
    }
    // Extract the name part by stripping trailing tag discriminator (#1234 or -1234)
    String searchName = nameOrBattleTag.replaceAll("[#\\-]\\d{3,6}$", "");
    log.info("[Overfast] Resolving '{}' -> searching by name='{}'", nameOrBattleTag, searchName);
    List<OverwatchPlayerSearchResultDto> results = searchPlayers(searchName);
    if (results.isEmpty()) {
      log.warn("[Overfast] Could not resolve player ID for '{}' — no results found", nameOrBattleTag);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Overwatch player not found: " + nameOrBattleTag);
    }
    String resolvedId = results.get(0).getPlayerId();
    log.info("[Overfast] Resolved '{}' -> internalId='{}'", nameOrBattleTag, resolvedId);
    return resolvedId;
  }

  public OverwatchPlayerSummaryDto getPlayerSummary(String playerId) {
    log.info("[Overfast] Fetching player summary for playerId='{}'", playerId);
    OverwatchPlayerSummaryDto summary =
        restClient
            .get()
            .uri("/players/{playerId}/summary", playerId)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
              log.warn("[Overfast] Player summary 4xx for playerId='{}' - status={}", playerId, response.getStatusCode());
              throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Overwatch player not found");
            })
            .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
              log.error("[Overfast] Player summary 5xx for playerId='{}' - status={}", playerId, response.getStatusCode());
              throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to validate Overwatch player");
            })
            .body(OverwatchPlayerSummaryDto.class);
    if (summary == null) {
      log.warn("[Overfast] Player summary returned null for playerId='{}'", playerId);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Overwatch player not found");
    }
    log.info("[Overfast] Player summary received for playerId='{}' -> username='{}'", playerId, summary.getUsername());
    return summary;
  }

  public OverfastStatsSummaryResponseDto getStatsSummary(String playerId, String gamemode, String platform) {
    log.info("[Overfast] Fetching stats summary for playerId='{}' gamemode='{}' platform='{}'",
        playerId, gamemode, platform);
    OverfastStatsSummaryResponseDto result = restClient
        .get()
        .uri(b -> {
          var builder = b.path("/players/{playerId}/stats/summary");
          if (gamemode != null && !gamemode.isBlank()) builder.queryParam("gamemode", gamemode);
          if (platform != null && !platform.isBlank()) builder.queryParam("platform", platform);
          return builder.build(playerId);
        })
        .retrieve()
        .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
          log.warn("[Overfast] Stats summary 4xx for playerId='{}' - status={}", playerId, response.getStatusCode());
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Overwatch stats not found");
        })
        .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
          log.error("[Overfast] Stats summary 5xx for playerId='{}' - status={}", playerId, response.getStatusCode());
          throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to fetch Overwatch stats");
        })
        .body(OverfastStatsSummaryResponseDto.class);
    if (result != null && result.getGeneral() != null) {
      log.info("[Overfast] Stats summary received for playerId='{}' -> gamesPlayed={} winrate={}",
          playerId, result.getGeneral().getGamesPlayed(), result.getGeneral().getWinrate());
    } else {
      log.warn("[Overfast] Stats summary returned empty/null general stats for playerId='{}'", playerId);
    }
    return result;
  }

  /**
   * Get all player data (summary + career stats). Uses the /players/{player_id} endpoint per the
   * Overfast API spec (v4+). The former /players/{player_id}/complete no longer exists.
   */
  public OverfastPlayerCompleteDto getCompletePlayerInfo(String playerId) {
    log.info("[Overfast] Fetching complete player info for playerId='{}'", playerId);
    try {
      OverfastPlayerCompleteDto result = restClient
          .get()
          .uri("/players/{playerId}", playerId)
          .retrieve()
          .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
            log.warn("[Overfast] Complete player info 4xx for playerId='{}' - status={}", playerId, response.getStatusCode());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Overwatch player not found");
          })
          .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
            log.error("[Overfast] Complete player info 5xx for playerId='{}' - status={}", playerId, response.getStatusCode());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to fetch Overwatch player data");
          })
          .body(OverfastPlayerCompleteDto.class);
      log.info("[Overfast] Complete player info received for playerId='{}'", playerId);
      return result;
    } catch (ResponseStatusException ex) {
      if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
        log.warn("[Overfast] Complete player info not found for playerId='{}' — falling back to summary endpoint", playerId);
        OverwatchPlayerSummaryDto summaryDto = getPlayerSummary(playerId);
        OverfastSummaryDto summary = new OverfastSummaryDto();
        summary.setUsername(summaryDto.getUsername());
        summary.setAvatar(summaryDto.getAvatar());
        OverfastPlayerCompleteDto fallback = new OverfastPlayerCompleteDto();
        fallback.setSummary(summary);
        log.info("[Overfast] Fallback summary built for playerId='{}' -> username='{}'", playerId, summaryDto.getUsername());
        return fallback;
      }
      throw ex;
    }
  }
}
