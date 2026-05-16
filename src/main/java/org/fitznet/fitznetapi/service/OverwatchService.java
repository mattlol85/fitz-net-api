package org.fitznet.fitznetapi.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.dto.overfast.OverfastCompetitiveDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastGeneralStatsDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastPlatformRanksDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastPlayerCompleteDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastRoleRankDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastStatsSummaryResponseDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastStatsTotalsDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastSummaryDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSearchResultDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSummaryDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchProfileDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchSeasonHistoryDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchStatsSnapshotDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.overwatch.CompetitiveRatings;
import org.fitznet.fitznetapi.service.overwatch.HistorySnapshot;
import org.fitznet.fitznetapi.service.overwatch.PlayerSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
public class OverwatchService {

  private final OverwatchClient overwatchClient;
  private final UserRepository userRepository;

  public OverwatchService(OverwatchClient overwatchClient, UserRepository userRepository) {
    this.overwatchClient = overwatchClient;
    this.userRepository = userRepository;
  }

  // ---- Public API ----

  public List<OverwatchPlayerSearchResultDto> searchPlayers(String name) {
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Player name is required");
    }
    return overwatchClient.searchPlayers(name);
  }

  public OverwatchProfileDto attachProfile(
      String username, String playerId, String gamemode, String platform) {
    if (playerId == null || playerId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "playerId is required");
    }

    String normalizedId = normalizeBattleTag(playerId);
    User user = findUser(username);

    OverwatchPlayerSummaryDto summary = overwatchClient.getPlayerSummary(normalizedId);
    OverfastStatsSummaryResponseDto statsSummary = safeGetStatsSummary(normalizedId, gamemode, platform);
    OverfastPlayerCompleteDto playerComplete = overwatchClient.getCompletePlayerInfo(normalizedId);

    OverwatchStatsSnapshotDto stats = extractStats(statsSummary);
    CompetitiveRatings ratings = extractCompetitiveRatings(playerComplete);
    PlayerSnapshot snapshot = extractPlayerSnapshot(playerComplete, summary, normalizedId);

    user.setOverwatchPlayerId(snapshot.getPlayerId());
    user.setOverwatchBattleTag(snapshot.getBattleTag());
    user.setOverwatchDisplayName(snapshot.getDisplayName());
    user.setOverwatchAvatarUrl(snapshot.getAvatarUrl());
    user.setOverwatchLastUpdatedAt(Instant.now());
    user.setOverwatchGamesWon(stats.getGamesWon());
    user.setOverwatchGamesPlayed(stats.getGamesPlayed());
    user.setOverwatchWinrate(stats.getWinrate());
    user.setOverwatchKda(stats.getKda());
    user.setOverwatchEliminations(stats.getEliminations());
    user.setOverwatchDeaths(stats.getDeaths());
    user.setOverwatchDamage(stats.getDamage());
    user.setOverwatchHealing(stats.getHealing());
    user.setOverwatchDpsRating(ratings.getDpsRating());
    user.setOverwatchTankRating(ratings.getTankRating());
    user.setOverwatchHealsRating(ratings.getHealsRating());

    User saved = userRepository.save(user);
    log.info("Attached Overwatch player {} to user {}", saved.getOverwatchPlayerId(), username);
    return toProfileDto(saved);
  }

  public OverwatchProfileDto getProfile(String username) {
    return toProfileDto(findUser(username));
  }

  public OverwatchSeasonHistoryDto getHistory(String username) {
    User user = findUser(username);
    if (user.getOverwatchPlayerId() == null || user.getOverwatchPlayerId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No Overwatch profile linked to this account");
    }
    return buildHistory(user.getOverwatchPlayerId(), user);
  }

  public OverwatchSeasonHistoryDto getHistoryForPlayer(String playerId) {
    if (playerId == null || playerId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "playerId is required");
    }
    return buildHistory(playerId, null);
  }

  public List<OverwatchProfileDto> getLeaderboard() {
    return userRepository.findAll().stream()
        .filter(u -> u.getOverwatchPlayerId() != null)
        .sorted(
            Comparator.comparing(
                    OverwatchService::leaderboardScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(
                    u -> defaultInteger(u.getOverwatchGamesWon()), Comparator.reverseOrder())
                .thenComparing(User::getUsername))
        .map(this::toProfileDto)
        .toList();
  }

  // ---- Private helpers ----

  private OverwatchSeasonHistoryDto buildHistory(String playerId, User linkedUser) {
    String normalizedId = normalizeBattleTag(playerId);
    OverwatchPlayerSummaryDto summary = overwatchClient.getPlayerSummary(normalizedId);
    OverfastPlayerCompleteDto playerComplete = overwatchClient.getCompletePlayerInfo(normalizedId);

    CompetitiveRatings ratings = extractCompetitiveRatings(playerComplete);
    PlayerSnapshot snapshot = extractPlayerSnapshot(playerComplete, summary, normalizedId);
    HistorySnapshot history = new HistorySnapshot(null, List.of(), List.of(), List.of());

    return OverwatchSeasonHistoryDto.builder()
        .username(linkedUser != null ? linkedUser.getUsername() : snapshot.getDisplayName())
        .playerId(snapshot.getPlayerId())
        .battleTag(snapshot.getBattleTag())
        .displayName(snapshot.getDisplayName())
        .avatarUrl(snapshot.getAvatarUrl())
        .currentSeason(history.getCurrentSeason())
        .dpsRating(ratings.getDpsRating())
        .tankRating(ratings.getTankRating())
        .healsRating(ratings.getHealsRating())
        .dpsHistory(history.getDpsHistory())
        .tankHistory(history.getTankHistory())
        .healsHistory(history.getHealsHistory())
        .rankedMatches(List.of())
        .build();
  }

  private static PlayerSnapshot extractPlayerSnapshot(
      OverfastPlayerCompleteDto playerComplete,
      OverwatchPlayerSummaryDto summary,
      String requestedId) {

    OverfastSummaryDto completeSummary = playerComplete != null ? playerComplete.getSummary() : null;

    String displayName = (completeSummary != null && completeSummary.getUsername() != null)
        ? completeSummary.getUsername()
        : (summary != null && summary.getName() != null ? summary.getName() : requestedId);

    String avatarUrl = (completeSummary != null && completeSummary.getAvatar() != null)
        ? completeSummary.getAvatar()
        : (summary != null ? summary.getAvatar() : null);

    return new PlayerSnapshot(requestedId, normalizeBattleTag(requestedId), displayName, avatarUrl);
  }

  private static CompetitiveRatings extractCompetitiveRatings(OverfastPlayerCompleteDto playerComplete) {

    OverfastSummaryDto completeSummary = playerComplete != null ? playerComplete.getSummary() : null;
    OverfastCompetitiveDto competitive = completeSummary != null ? completeSummary.getCompetitive() : null;

    if (competitive == null) {
      return new CompetitiveRatings(null, null, null);
    }

    OverfastPlatformRanksDto platform = competitive.getPc() != null
        ? competitive.getPc()
        : competitive.getConsole();

    if (platform == null) {
      return new CompetitiveRatings(null, null, null);
    }

    return new CompetitiveRatings(
        rankToApproximateSr(platform.getDamage()),
        rankToApproximateSr(platform.getTank()),
        rankToApproximateSr(platform.getSupport()));
  }

  private static OverwatchStatsSnapshotDto extractStats(OverfastStatsSummaryResponseDto statsSummary) {
    if (statsSummary == null) {
      return OverwatchStatsSnapshotDto.builder().build();
    }

    OverfastGeneralStatsDto general = statsSummary.getGeneral();
    if (general == null) {
      return OverwatchStatsSnapshotDto.builder().build();
    }

    OverfastStatsTotalsDto totals = general.getTotal();
    Integer eliminations = totals != null ? totals.getEliminations() : null;
    Integer deaths = totals != null ? totals.getDeaths() : null;
    Integer damage = totals != null ? totals.getDamage() : null;
    Integer healing = totals != null ? totals.getHealing() : null;
    Integer gamesWon = general.getGamesWon() != null
        ? general.getGamesWon()
        : (totals != null ? totals.getWins() : null);
    Double winrate = general.getWinrate();
    Double kda = general.getKda();
    Integer gamesPlayed = general.getGamesPlayed();

    if (winrate == null && gamesWon != null && gamesPlayed != null && gamesPlayed > 0) {
      winrate = (gamesWon * 100.0) / gamesPlayed;
    }
    if (kda == null && eliminations != null && deaths != null) {
      kda = eliminations / (double) Math.max(deaths, 1);
    }

    return OverwatchStatsSnapshotDto.builder()
        .gamesWon(gamesWon)
        .gamesPlayed(gamesPlayed)
        .winrate(winrate)
        .kda(kda)
        .eliminations(eliminations)
        .deaths(deaths)
        .damage(damage)
        .healing(healing)
        .build();
  }

  /**
   * Converts an Overwatch 2 competitive rank (division + tier) to an approximate numeric SR.
   * Each division spans 500 points; tier 5 is the lowest and tier 1 is the highest within a division.
   */
  private static Integer rankToApproximateSr(OverfastRoleRankDto rank) {
    if (rank == null || rank.getDivision() == null) {
      return null;
    }
    int base = switch (rank.getDivision().toLowerCase(Locale.ROOT)) {
      case "bronze"      -> 0;
      case "silver"      -> 500;
      case "gold"        -> 1000;
      case "platinum"    -> 1500;
      case "diamond"     -> 2000;
      case "master"      -> 2500;
      case "grandmaster" -> 3000;
      case "champion"    -> 3500;
      default            -> 0;
    };
    int tierOffset = rank.getTier() != null ? (5 - rank.getTier()) * 100 : 0;
    return base + tierOffset;
  }

  private OverfastStatsSummaryResponseDto safeGetStatsSummary(
      String playerId, String gamemode, String platform) {
    try {
      return overwatchClient.getStatsSummary(playerId, gamemode, platform);
    } catch (Exception e) {
      log.warn("Could not fetch stats summary for {}: {}", playerId, e.getMessage());
      return null;
    }
  }

  private static Double leaderboardScore(User user) {
    double sum = 0;
    int count = 0;
    if (user.getOverwatchDpsRating() != null)   { sum += user.getOverwatchDpsRating();   count++; }
    if (user.getOverwatchTankRating() != null)  { sum += user.getOverwatchTankRating();  count++; }
    if (user.getOverwatchHealsRating() != null) { sum += user.getOverwatchHealsRating(); count++; }
    return count > 0 ? sum / count : user.getOverwatchWinrate();
  }

  private static Integer defaultInteger(Integer v) {
    return v == null ? 0 : v;
  }

  private User findUser(String username) {
    User user = userRepository.findByUsername(username);
    if (user == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }
    return user;
  }

  private OverwatchProfileDto toProfileDto(User user) {
    return OverwatchProfileDto.builder()
        .username(user.getUsername())
        .playerId(user.getOverwatchPlayerId())
        .battleTag(user.getOverwatchBattleTag())
        .displayName(user.getOverwatchDisplayName())
        .avatarUrl(user.getOverwatchAvatarUrl())
        .lastUpdatedAt(user.getOverwatchLastUpdatedAt())
        .gamesWon(user.getOverwatchGamesWon())
        .gamesPlayed(user.getOverwatchGamesPlayed())
        .winrate(user.getOverwatchWinrate())
        .kda(user.getOverwatchKda())
        .eliminations(user.getOverwatchEliminations())
        .deaths(user.getOverwatchDeaths())
        .damage(user.getOverwatchDamage())
        .healing(user.getOverwatchHealing())
        .dpsRating(user.getOverwatchDpsRating())
        .tankRating(user.getOverwatchTankRating())
        .healsRating(user.getOverwatchHealsRating())
        .build();
  }

  private static String normalizeBattleTag(String value) {
    if (value == null) return null;
    return value.trim().replace('#', '-').replaceAll("\\s+", "");
  }
}

