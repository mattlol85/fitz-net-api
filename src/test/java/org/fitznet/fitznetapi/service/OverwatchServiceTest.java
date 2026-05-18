package org.fitznet.fitznetapi.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.fitznet.fitznetapi.dto.overfast.OverfastGeneralStatsDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastPlayerCompleteDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastStatsSummaryResponseDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastStatsTotalsDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSummaryDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchProfileDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchSeasonHistoryDto;
import org.fitznet.fitznetapi.model.OverwatchRatingSnapshot;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.OverwatchRatingSnapshotRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.overwatch.CompetitiveRatings;
import org.fitznet.fitznetapi.service.overwatch.HeroStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class OverwatchServiceTest {

  @Mock private OverwatchClient overwatchClient;
  @Mock private UserRepository userRepository;
  @Mock private OverwatchRatingSnapshotRepository snapshotRepository;
  @Mock private OverwatchRefreshService refreshService;

  private AutoCloseable mocks;
  private OverwatchService overwatchService;

  @BeforeEach
  void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    overwatchService = new OverwatchService(overwatchClient, userRepository, snapshotRepository, refreshService);
    when(refreshService.getCurrentSeason()).thenReturn("Season 16");
  }

  @AfterEach
  void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }

  @Test
  void attachProfileShouldValidateAndSaveOverwatchStats() {
    User user = User.builder().id("user-1").username("matt").email("matt@example.com").build();

    OverwatchPlayerSummaryDto summary = new OverwatchPlayerSummaryDto();
    summary.setUsername("Matt#1234");
    summary.setAvatar("https://example.com/avatar.png");

    OverfastStatsTotalsDto totals = new OverfastStatsTotalsDto();
    totals.setEliminations(2100);
    totals.setDeaths(700);

    OverfastGeneralStatsDto general = new OverfastGeneralStatsDto();
    general.setGamesWon(42);
    general.setGamesPlayed(84);
    general.setTotal(totals);

    OverfastStatsSummaryResponseDto statsSummary = new OverfastStatsSummaryResponseDto();
    statsSummary.setGeneral(general);

    OverfastPlayerCompleteDto playerComplete = new OverfastPlayerCompleteDto();

    when(userRepository.findByUsername("matt")).thenReturn(user);
    when(overwatchClient.resolveInternalPlayerId("Matt-1234")).thenReturn("Matt-1234");
    when(overwatchClient.getPlayerSummary("Matt-1234")).thenReturn(summary);
    when(overwatchClient.getStatsSummary("Matt-1234", "competitive", "pc")).thenReturn(statsSummary);
    when(overwatchClient.getCompletePlayerInfo("Matt-1234")).thenReturn(playerComplete);
    when(userRepository.save(any(User.class))).thenReturn(user);

    OverwatchProfileDto result =
        overwatchService.attachProfile("matt", "Matt-1234", "competitive", "pc");

    assertEquals("Matt-1234", result.getPlayerId());
    assertEquals("Matt#1234", result.getDisplayName());
    assertEquals(42, result.getGamesWon());
    assertEquals(84, result.getGamesPlayed());
    assertEquals(50.0, result.getWinrate());
    assertEquals(3.0, result.getKda());
    assertNotNull(result.getLastUpdatedAt());
    verify(refreshService, times(1)).saveSnapshotAndUpdatePeaks(any(User.class), any(CompetitiveRatings.class));
  }

  @Test
  void getLeaderboardShouldSortByWinrateThenGamesWon() {
    User best =
        User.builder()
            .username("best")
            .overwatchPlayerId("Best-1")
            .overwatchWinrate(65.0)
            .overwatchGamesWon(20)
            .build();
    User tieWinner =
        User.builder()
            .username("tieWinner")
            .overwatchPlayerId("Tie-1")
            .overwatchWinrate(55.0)
            .overwatchGamesWon(30)
            .build();
    User tieLoser =
        User.builder()
            .username("tieLoser")
            .overwatchPlayerId("Tie-2")
            .overwatchWinrate(55.0)
            .overwatchGamesWon(10)
            .build();
    User unattached = User.builder().username("nope").build();

    when(userRepository.findAll()).thenReturn(List.of(tieLoser, unattached, tieWinner, best));

    List<OverwatchProfileDto> leaderboard = overwatchService.getLeaderboard();

    assertEquals(
        List.of("best", "tieWinner", "tieLoser"),
        leaderboard.stream().map(OverwatchProfileDto::getUsername).toList());
  }

  // ---- getHistory / topHeroHistories ----

  @Test
  void getHistoryShouldBuildTopHeroHistoriesFromSnapshotsOrderedByTimePlayed() {
    User user = User.builder()
        .id("u1").username("matt")
        .overwatchPlayerId("Matt-1733")
        .build();

    List<HeroStats> heroStats = List.of(
        HeroStats.builder().heroKey("mercy").timePlayed(3600L).winPercentage(65.0).gamesWon(10).build(),
        HeroStats.builder().heroKey("moira").timePlayed(1800L).winPercentage(50.0).gamesWon(5).build(),
        HeroStats.builder().heroKey("kiriko").timePlayed(900L).winPercentage(40.0).gamesWon(3).build()
    );

    OverwatchRatingSnapshot snap = OverwatchRatingSnapshot.builder()
        .userId("u1")
        .season("Season 16")
        .recordedAt(Instant.parse("2025-01-01T12:00:00Z"))
        .dpsRating(2300)
        .heroStats(heroStats)
        .build();

    when(userRepository.findByUsername("matt")).thenReturn(user);
    when(overwatchClient.resolveInternalPlayerId("Matt-1733")).thenReturn("Matt-1733");
    when(overwatchClient.getPlayerSummary("Matt-1733")).thenReturn(new OverwatchPlayerSummaryDto());
    when(overwatchClient.getCompletePlayerInfo("Matt-1733")).thenReturn(new OverfastPlayerCompleteDto());
    when(snapshotRepository.findByUserIdAndSeasonOrderByRecordedAtAsc("u1", "Season 16"))
        .thenReturn(List.of(snap));
    when(snapshotRepository.findByUserIdOrderByRecordedAtAsc("u1"))
        .thenReturn(List.of(snap));

    OverwatchSeasonHistoryDto history = overwatchService.getHistory("matt");

    assertNotNull(history.getTopHeroHistories());
    assertEquals(3, history.getTopHeroHistories().size());

    // Sorted by descending time played: mercy > moira > kiriko
    assertEquals("mercy", history.getTopHeroHistories().get(0).getHeroKey());
    assertEquals("Mercy", history.getTopHeroHistories().get(0).getHeroName());
    assertEquals("moira", history.getTopHeroHistories().get(1).getHeroKey());
    assertEquals("kiriko", history.getTopHeroHistories().get(2).getHeroKey());

    // Each timeline entry carries win rate and a label
    assertEquals(1, history.getTopHeroHistories().get(0).getHistory().size());
    assertEquals(65.0, history.getTopHeroHistories().get(0).getHistory().get(0).getWinRate());
    assertNotNull(history.getTopHeroHistories().get(0).getHistory().get(0).getLabel());
  }

  @Test
  void getHistoryShouldReturnEmptyTopHeroHistoriesWhenNoSnapshotsExist() {
    User user = User.builder()
        .id("u1").username("matt")
        .overwatchPlayerId("Matt-1733")
        .build();

    when(userRepository.findByUsername("matt")).thenReturn(user);
    when(overwatchClient.resolveInternalPlayerId("Matt-1733")).thenReturn("Matt-1733");
    when(overwatchClient.getPlayerSummary("Matt-1733")).thenReturn(new OverwatchPlayerSummaryDto());
    when(overwatchClient.getCompletePlayerInfo("Matt-1733")).thenReturn(new OverfastPlayerCompleteDto());
    when(snapshotRepository.findByUserIdAndSeasonOrderByRecordedAtAsc("u1", "Season 16"))
        .thenReturn(List.of());
    when(snapshotRepository.findByUserIdOrderByRecordedAtAsc("u1"))
        .thenReturn(List.of());

    OverwatchSeasonHistoryDto history = overwatchService.getHistory("matt");

    assertNotNull(history.getTopHeroHistories());
    assertTrue(history.getTopHeroHistories().isEmpty());
  }

  @Test
  void getHistoryShouldLimitToTop3HeroesByTimePlayed() {
    User user = User.builder()
        .id("u1").username("matt")
        .overwatchPlayerId("Matt-1733")
        .build();

    List<HeroStats> heroStats = List.of(
        HeroStats.builder().heroKey("mercy").timePlayed(5000L).winPercentage(60.0).build(),
        HeroStats.builder().heroKey("moira").timePlayed(4000L).winPercentage(55.0).build(),
        HeroStats.builder().heroKey("kiriko").timePlayed(3000L).winPercentage(50.0).build(),
        HeroStats.builder().heroKey("ana").timePlayed(2000L).winPercentage(45.0).build(),
        HeroStats.builder().heroKey("lucio").timePlayed(1000L).winPercentage(40.0).build()
    );

    OverwatchRatingSnapshot snap = OverwatchRatingSnapshot.builder()
        .userId("u1").season("Season 16")
        .recordedAt(Instant.parse("2025-01-01T12:00:00Z"))
        .heroStats(heroStats)
        .build();

    when(userRepository.findByUsername("matt")).thenReturn(user);
    when(overwatchClient.resolveInternalPlayerId("Matt-1733")).thenReturn("Matt-1733");
    when(overwatchClient.getPlayerSummary("Matt-1733")).thenReturn(new OverwatchPlayerSummaryDto());
    when(overwatchClient.getCompletePlayerInfo("Matt-1733")).thenReturn(new OverfastPlayerCompleteDto());
    when(snapshotRepository.findByUserIdAndSeasonOrderByRecordedAtAsc("u1", "Season 16"))
        .thenReturn(List.of(snap));
    when(snapshotRepository.findByUserIdOrderByRecordedAtAsc("u1"))
        .thenReturn(List.of(snap));

    OverwatchSeasonHistoryDto history = overwatchService.getHistory("matt");

    assertEquals(3, history.getTopHeroHistories().size());
    assertEquals(List.of("mercy", "moira", "kiriko"),
        history.getTopHeroHistories().stream()
            .map(h -> h.getHeroKey()).toList());
  }
}
