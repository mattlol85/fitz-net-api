package org.fitznet.fitznetapi.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.dto.overfast.OverfastGeneralStatsDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastPlayerCompleteDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastStatsSummaryResponseDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastStatsTotalsDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastSummaryDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSearchResultDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSummaryDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchProfileDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchSeasonHistoryDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchSeasonHistoryPointDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchStatsSnapshotDto;
import org.fitznet.fitznetapi.model.OverwatchRatingSnapshot;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.model.overwatch.OverwatchProfile;
import org.fitznet.fitznetapi.repository.OverwatchRatingSnapshotRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.overwatch.CompetitiveRatings;
import org.fitznet.fitznetapi.service.overwatch.PlayerSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
public class OverwatchService {

  private final OverwatchClient overwatchClient;
  private final UserRepository userRepository;
  private final OverwatchRatingSnapshotRepository snapshotRepository;
  private final OverwatchRefreshService refreshService;

  public OverwatchService(
      OverwatchClient overwatchClient,
      UserRepository userRepository,
      OverwatchRatingSnapshotRepository snapshotRepository,
      OverwatchRefreshService refreshService) {
    this.overwatchClient = overwatchClient;
    this.userRepository = userRepository;
    this.snapshotRepository = snapshotRepository;
    this.refreshService = refreshService;
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

    String normalizedInput = normalizeBattleTag(playerId);
    String internalId = overwatchClient.resolveInternalPlayerId(normalizedInput);
    User user = findUser(username);

    OverwatchPlayerSummaryDto summary = overwatchClient.getPlayerSummary(internalId);
    OverfastStatsSummaryResponseDto statsSummary = safeGetStatsSummary(internalId, gamemode, platform);
    OverfastPlayerCompleteDto playerComplete = overwatchClient.getCompletePlayerInfo(internalId);

    OverwatchStatsSnapshotDto stats = extractStats(statsSummary);
    CompetitiveRatings ratings = OverwatchRefreshService.extractCompetitiveRatings(playerComplete);
    PlayerSnapshot snapshot = extractPlayerSnapshot(playerComplete, summary, internalId, normalizedInput);

    OverwatchProfile profile = ensureProfile(user);
    profile.setPlayerId(snapshot.getPlayerId());
    profile.setBattleTag(snapshot.getBattleTag());
    profile.setDisplayName(snapshot.getDisplayName());
    profile.setAvatarUrl(snapshot.getAvatarUrl());
    profile.setLastUpdatedAt(Instant.now());
    profile.setGamesWon(stats.getGamesWon());
    profile.setGamesPlayed(stats.getGamesPlayed());
    profile.setWinrate(stats.getWinrate());
    profile.setKda(stats.getKda());
    profile.setEliminations(stats.getEliminations());
    profile.setDeaths(stats.getDeaths());
    profile.setDamage(stats.getDamage());
    profile.setHealing(stats.getHealing());

    // Save first so the user has an ID for the snapshot row.
    User saved = userRepository.save(user);

    // Record the snapshot and update peaks on the embedded profile; persists in a single save.
    refreshService.saveSnapshotAndUpdatePeaks(saved, ratings);

    log.info("Attached Overwatch player {} to user {}", saved.getOverwatch().getPlayerId(), username);
    return toProfileDto(saved);
  }

  public OverwatchProfileDto getProfile(String username) {
    return toProfileDto(findUser(username));
  }

  public OverwatchSeasonHistoryDto getHistory(String username) {
    User user = findUser(username);
    OverwatchProfile profile = user.getOverwatch();
    if (profile == null || profile.getPlayerId() == null || profile.getPlayerId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No Overwatch profile linked to this account");
    }
    return buildHistory(profile.getPlayerId(), profile.getBattleTag(), user);
  }

  public OverwatchSeasonHistoryDto getHistoryForPlayer(String playerId) {
    if (playerId == null || playerId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "playerId is required");
    }
    String normalizedInput = normalizeBattleTag(playerId);
    return buildHistory(normalizedInput, normalizedInput, null);
  }

  public List<OverwatchProfileDto> getLeaderboard() {
    return userRepository.findAll().stream()
        .filter(u -> u.getOverwatch() != null && u.getOverwatch().getPlayerId() != null)
        .sorted(
            Comparator.comparing(
                    OverwatchService::leaderboardScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(
                    u -> defaultInteger(u.getOverwatch().getGamesWon()), Comparator.reverseOrder())
                .thenComparing(User::getUsername))
        .map(this::toProfileDto)
        .toList();
  }

  // ---- Private helpers ----

  private OverwatchSeasonHistoryDto buildHistory(String playerId, String displayBattleTag, User linkedUser) {
    OverwatchProfile linkedProfile = linkedUser != null ? linkedUser.getOverwatch() : null;
    String internalId = overwatchClient.resolveInternalPlayerId(playerId);
    OverwatchPlayerSummaryDto summary = overwatchClient.getPlayerSummary(internalId);
    OverfastPlayerCompleteDto playerComplete = overwatchClient.getCompletePlayerInfo(internalId);

    CompetitiveRatings ratings = OverwatchRefreshService.extractCompetitiveRatings(playerComplete);
    PlayerSnapshot snapshot = extractPlayerSnapshot(playerComplete, summary, internalId, displayBattleTag);

    String season = refreshService.getCurrentSeason();
    List<OverwatchSeasonHistoryPointDto> dpsHistory = List.of();
    List<OverwatchSeasonHistoryPointDto> tankHistory = List.of();
    List<OverwatchSeasonHistoryPointDto> healsHistory = List.of();
    List<OverwatchSeasonHistoryPointDto> dpsSeasonHistory = List.of();
    List<OverwatchSeasonHistoryPointDto> tankSeasonHistory = List.of();
    List<OverwatchSeasonHistoryPointDto> healsSeasonHistory = List.of();

    if (linkedUser != null && linkedUser.getId() != null) {
      List<OverwatchRatingSnapshot> snapshots =
          snapshotRepository.findByUserIdAndSeasonOrderByRecordedAtAsc(linkedUser.getId(), season);

      dpsHistory = snapshots.stream()
          .filter(s -> s.getDpsRating() != null)
          .map(s -> OverwatchSeasonHistoryPointDto.builder()
              .label(formatSnapshotLabel(s))
              .rating(s.getDpsRating())
              .recordedAt(s.getRecordedAt())
              .build())
          .toList();

      tankHistory = snapshots.stream()
          .filter(s -> s.getTankRating() != null)
          .map(s -> OverwatchSeasonHistoryPointDto.builder()
              .label(formatSnapshotLabel(s))
              .rating(s.getTankRating())
              .recordedAt(s.getRecordedAt())
              .build())
          .toList();

      healsHistory = snapshots.stream()
          .filter(s -> s.getHealsRating() != null)
          .map(s -> OverwatchSeasonHistoryPointDto.builder()
              .label(formatSnapshotLabel(s))
              .rating(s.getHealsRating())
              .recordedAt(s.getRecordedAt())
              .build())
          .toList();

      // Cross-season history: latest snapshot per season, ordered chronologically
      List<OverwatchRatingSnapshot> allSnapshots =
          snapshotRepository.findByUserIdOrderByRecordedAtAsc(linkedUser.getId());

      java.util.Map<String, OverwatchRatingSnapshot> latestBySeason = new java.util.LinkedHashMap<>();
      for (OverwatchRatingSnapshot s : allSnapshots) {
        if (s.getSeason() != null) latestBySeason.put(s.getSeason(), s);
      }

      dpsSeasonHistory = latestBySeason.values().stream()
          .filter(s -> s.getDpsRating() != null)
          .map(s -> OverwatchSeasonHistoryPointDto.builder()
              .label(s.getSeason())
              .rating(s.getDpsRating())
              .recordedAt(s.getRecordedAt())
              .build())
          .toList();

      tankSeasonHistory = latestBySeason.values().stream()
          .filter(s -> s.getTankRating() != null)
          .map(s -> OverwatchSeasonHistoryPointDto.builder()
              .label(s.getSeason())
              .rating(s.getTankRating())
              .recordedAt(s.getRecordedAt())
              .build())
          .toList();

      healsSeasonHistory = latestBySeason.values().stream()
          .filter(s -> s.getHealsRating() != null)
          .map(s -> OverwatchSeasonHistoryPointDto.builder()
              .label(s.getSeason())
              .rating(s.getHealsRating())
              .recordedAt(s.getRecordedAt())
              .build())
          .toList();
    }

    return OverwatchSeasonHistoryDto.builder()
        .username(linkedUser != null ? linkedUser.getUsername() : snapshot.getDisplayName())
        .playerId(snapshot.getPlayerId())
        .battleTag(snapshot.getBattleTag())
        .displayName(snapshot.getDisplayName())
        .avatarUrl(snapshot.getAvatarUrl())
        .currentSeason(season)
        .dpsRating(ratings.getDpsRating())
        .tankRating(ratings.getTankRating())
        .healsRating(ratings.getHealsRating())
        .dpsPeakRating(linkedProfile != null ? linkedProfile.getDpsPeakRating() : null)
        .tankPeakRating(linkedProfile != null ? linkedProfile.getTankPeakRating() : null)
        .healsPeakRating(linkedProfile != null ? linkedProfile.getHealsPeakRating() : null)
        .dpsRankIcon(ratings.getDpsRankIcon())
        .tankRankIcon(ratings.getTankRankIcon())
        .healsRankIcon(ratings.getHealsRankIcon())
        .dpsHistory(dpsHistory)
        .tankHistory(tankHistory)
        .healsHistory(healsHistory)
        .dpsSeasonHistory(dpsSeasonHistory)
        .tankSeasonHistory(tankSeasonHistory)
        .healsSeasonHistory(healsSeasonHistory)
        .rankedMatches(List.of())
        .build();
  }

  private static String formatSnapshotLabel(OverwatchRatingSnapshot snapshot) {
    if (snapshot.getRecordedAt() == null) return "Unknown";
    java.time.ZonedDateTime zdt = snapshot.getRecordedAt().atZone(java.time.ZoneOffset.UTC);
    return String.format("%s %d", zdt.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ROOT), zdt.getDayOfMonth());
  }

  private static PlayerSnapshot extractPlayerSnapshot(
      OverfastPlayerCompleteDto playerComplete,
      OverwatchPlayerSummaryDto summary,
      String internalId,
      String battleTagInput) {

    OverfastSummaryDto completeSummary = playerComplete != null ? playerComplete.getSummary() : null;

    String displayName = (completeSummary != null && completeSummary.getUsername() != null)
        ? completeSummary.getUsername()
        : (summary != null && summary.getUsername() != null ? summary.getUsername() : internalId);

    String avatarUrl = (completeSummary != null && completeSummary.getAvatar() != null)
        ? completeSummary.getAvatar()
        : (summary != null ? summary.getAvatar() : null);

    return new PlayerSnapshot(internalId, battleTagInput, displayName, avatarUrl);
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
    Integer gamesWon = general.getGamesWon();
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
    OverwatchProfile profile = user.getOverwatch();
    double sum = 0;
    int count = 0;
    if (profile.getDpsRating() != null)   { sum += profile.getDpsRating();   count++; }
    if (profile.getTankRating() != null)  { sum += profile.getTankRating();  count++; }
    if (profile.getHealsRating() != null) { sum += profile.getHealsRating(); count++; }
    return count > 0 ? sum / count : profile.getWinrate();
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
    OverwatchProfile profile = user.getOverwatch();
    if (profile == null) {
      // Unlinked account: return a DTO carrying only the username.
      return OverwatchProfileDto.builder().username(user.getUsername()).build();
    }
    return OverwatchProfileDto.builder()
        .username(user.getUsername())
        .playerId(profile.getPlayerId())
        .battleTag(profile.getBattleTag())
        .displayName(profile.getDisplayName())
        .avatarUrl(profile.getAvatarUrl())
        .lastUpdatedAt(profile.getLastUpdatedAt())
        .gamesWon(profile.getGamesWon())
        .gamesPlayed(profile.getGamesPlayed())
        .winrate(profile.getWinrate())
        .kda(profile.getKda())
        .eliminations(profile.getEliminations())
        .deaths(profile.getDeaths())
        .damage(profile.getDamage())
        .healing(profile.getHealing())
        .dpsRating(profile.getDpsRating())
        .tankRating(profile.getTankRating())
        .healsRating(profile.getHealsRating())
        .dpsPeakRating(profile.getDpsPeakRating())
        .tankPeakRating(profile.getTankPeakRating())
        .healsPeakRating(profile.getHealsPeakRating())
        .build();
  }

  /** Returns the user's embedded Overwatch profile, creating and attaching it if absent. */
  private static OverwatchProfile ensureProfile(User user) {
    OverwatchProfile profile = user.getOverwatch();
    if (profile == null) {
      profile = new OverwatchProfile();
      user.setOverwatch(profile);
    }
    return profile;
  }

  private static String normalizeBattleTag(String value) {
    if (value == null) return null;
    return value.trim().replace('#', '-').replaceAll("\\s+", "");
  }
}
