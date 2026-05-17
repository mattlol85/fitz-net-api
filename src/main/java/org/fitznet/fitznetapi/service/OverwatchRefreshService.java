package org.fitznet.fitznetapi.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.dto.overfast.OverfastCompetitiveDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastPlatformRanksDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastPlayerCompleteDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastRoleRankDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastSummaryDto;
import org.fitznet.fitznetapi.model.OverwatchRatingSnapshot;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.OverwatchRatingSnapshotRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.overwatch.CompetitiveRatings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Handles periodic and on-demand refresh of Overwatch player data from OverFast.
 * Records rating snapshots and tracks all-time peaks for each role.
 *
 * <p>The cron job processes users sequentially with a configurable inter-request delay to stay
 * safely within OverFast''s adaptive Blizzard throttle (2.0s initial delay). OverFast returns 503
 * when Blizzard is rate-limiting; these are handled gracefully by skipping the user and retrying
 * on the next cron cycle.
 */
@Slf4j
@Service
public class OverwatchRefreshService {

  private final OverwatchClient overwatchClient;
  private final UserRepository userRepository;
  private final OverwatchRatingSnapshotRepository snapshotRepository;

  @Value("${overwatch.refresh.inter-request-delay-ms:3000}")
  private long interRequestDelayMs;

  @Value("${overwatch.refresh.cooldown-minutes:20}")
  private long cooldownMinutes;

  @Value("${overwatch.current-season:Season 16}")
  private String currentSeason;

  public OverwatchRefreshService(
      OverwatchClient overwatchClient,
      UserRepository userRepository,
      OverwatchRatingSnapshotRepository snapshotRepository) {
    this.overwatchClient = overwatchClient;
    this.userRepository = userRepository;
    this.snapshotRepository = snapshotRepository;
  }

  // ---- Cron job ----

  @Scheduled(cron = "${overwatch.refresh.cron:0 */30 * * * *}")
  public void refreshAllLinkedUsers() {
    List<User> linked = userRepository.findAll().stream()
        .filter(u -> u.getOverwatchPlayerId() != null && !u.getOverwatchPlayerId().isBlank())
        .toList();

    if (linked.isEmpty()) {
      log.info("[OW-Cron] No linked Overwatch users found — skipping refresh cycle.");
      return;
    }

    log.info("[OW-Cron] Starting refresh cycle for {} linked user(s).", linked.size());
    int refreshed = 0;
    int skipped = 0;
    int failed = 0;

    for (User user : linked) {
      if (isWithinCooldown(user)) {
        log.debug("[OW-Cron] Skipping {} — refreshed within cooldown window.", user.getUsername());
        skipped++;
        continue;
      }

      try {
        CompetitiveRatings ratings = fetchCurrentRatings(user.getOverwatchPlayerId());
        saveSnapshotAndUpdatePeaks(user, ratings);
        refreshed++;
      } catch (Exception e) {
        if (isOverfastRateLimited(e)) {
          log.warn("[OW-Cron] OverFast 503 for user ''{}'' — Blizzard throttle active, will retry next cycle.", user.getUsername());
        } else {
          log.warn("[OW-Cron] Failed to refresh user ''{}}'': {}", user.getUsername(), e.getMessage());
        }
        failed++;
      }

      if (interRequestDelayMs > 0) {
        try {
          Thread.sleep(interRequestDelayMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          log.warn("[OW-Cron] Refresh cycle interrupted — stopping early.");
          break;
        }
      }
    }

    log.info("[OW-Cron] Cycle complete. refreshed={} skipped={} failed={}", refreshed, skipped, failed);
  }

  // ---- Public API ----

  /**
   * Fetches current competitive ratings from OverFast for the given player.
   * Used by both the cron and OverwatchService.attachProfile().
   */
  public CompetitiveRatings fetchCurrentRatings(String playerId) {
    OverfastPlayerCompleteDto playerComplete = overwatchClient.getCompletePlayerInfo(playerId);
    return extractCompetitiveRatings(playerComplete);
  }

  /**
   * Records a snapshot of current ratings and updates all-time peak fields on the user if any
   * role has reached a new high. Persists both the snapshot and the user changes.
   */
  public void saveSnapshotAndUpdatePeaks(User user, CompetitiveRatings ratings) {
    Instant now = Instant.now();

    OverwatchRatingSnapshot snapshot = OverwatchRatingSnapshot.builder()
        .userId(user.getId())
        .season(currentSeason)
        .recordedAt(now)
        .dpsRating(ratings.getDpsRating())
        .tankRating(ratings.getTankRating())
        .healsRating(ratings.getHealsRating())
        .build();
    snapshotRepository.save(snapshot);

    boolean peakUpdated = false;

    if (ratings.getDpsRating() != null
        && (user.getOverwatchDpsPeakRating() == null
            || ratings.getDpsRating() > user.getOverwatchDpsPeakRating())) {
      user.setOverwatchDpsPeakRating(ratings.getDpsRating());
      peakUpdated = true;
    }
    if (ratings.getTankRating() != null
        && (user.getOverwatchTankPeakRating() == null
            || ratings.getTankRating() > user.getOverwatchTankPeakRating())) {
      user.setOverwatchTankPeakRating(ratings.getTankRating());
      peakUpdated = true;
    }
    if (ratings.getHealsRating() != null
        && (user.getOverwatchHealsPeakRating() == null
            || ratings.getHealsRating() > user.getOverwatchHealsPeakRating())) {
      user.setOverwatchHealsPeakRating(ratings.getHealsRating());
      peakUpdated = true;
    }

    user.setOverwatchDpsRating(ratings.getDpsRating());
    user.setOverwatchTankRating(ratings.getTankRating());
    user.setOverwatchHealsRating(ratings.getHealsRating());
    user.setOverwatchLastUpdatedAt(now);
    userRepository.save(user);

    if (peakUpdated) {
      log.info("[OW-Refresh] New peak rating(s) recorded for user ''{}''.", user.getUsername());
    }
    log.debug("[OW-Refresh] Snapshot saved for user ''{}'' season=''{}'' at {}.", user.getUsername(), currentSeason, now);
  }

  public String getCurrentSeason() {
    return currentSeason;
  }

  // ---- Private helpers ----

  private boolean isWithinCooldown(User user) {
    if (user.getOverwatchLastUpdatedAt() == null) return false;
    Instant cutoff = Instant.now().minus(cooldownMinutes, ChronoUnit.MINUTES);
    return user.getOverwatchLastUpdatedAt().isAfter(cutoff);
  }

  static boolean isOverfastRateLimited(Exception e) {
    if (e instanceof ResponseStatusException rse) {
      return rse.getStatusCode().value() == 503;
    }
    String msg = e.getMessage();
    return msg != null && msg.contains("503");
  }

  static CompetitiveRatings extractCompetitiveRatings(OverfastPlayerCompleteDto playerComplete) {
    OverfastSummaryDto completeSummary = playerComplete != null ? playerComplete.getSummary() : null;
    OverfastCompetitiveDto competitive = completeSummary != null ? completeSummary.getCompetitive() : null;

    if (competitive == null) {
      return new CompetitiveRatings(null, null, null, null, null, null);
    }

    OverfastPlatformRanksDto platform = competitive.getPc() != null
        ? competitive.getPc()
        : competitive.getConsole();

    if (platform == null) {
      return new CompetitiveRatings(null, null, null, null, null, null);
    }

    OverfastRoleRankDto damage = platform.getDamage();
    OverfastRoleRankDto tank   = platform.getTank();
    OverfastRoleRankDto support = platform.getSupport();

    return new CompetitiveRatings(
        rankToApproximateSr(damage),
        rankToApproximateSr(tank),
        rankToApproximateSr(support),
        damage  != null ? damage.getRankIcon()  : null,
        tank    != null ? tank.getRankIcon()    : null,
        support != null ? support.getRankIcon() : null);
  }

  /**
   * Converts an Overwatch 2 competitive rank (division + tier) to an approximate numeric SR.
   * Each division spans 500 points; tier 5 is the lowest and tier 1 is the highest.
   */
  static Integer rankToApproximateSr(OverfastRoleRankDto rank) {
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
}
