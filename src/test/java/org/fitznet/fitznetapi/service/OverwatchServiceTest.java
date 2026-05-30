package org.fitznet.fitznetapi.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.fitznet.fitznetapi.dto.overfast.OverfastGeneralStatsDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastPlayerCompleteDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastStatsSummaryResponseDto;
import org.fitznet.fitznetapi.dto.overfast.OverfastStatsTotalsDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSummaryDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchProfileDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.model.overwatch.OverwatchProfile;
import org.fitznet.fitznetapi.repository.OverwatchRatingSnapshotRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.service.overwatch.CompetitiveRatings;
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
            .overwatch(OverwatchProfile.builder()
                .playerId("Best-1").winrate(65.0).gamesWon(20).build())
            .build();
    User tieWinner =
        User.builder()
            .username("tieWinner")
            .overwatch(OverwatchProfile.builder()
                .playerId("Tie-1").winrate(55.0).gamesWon(30).build())
            .build();
    User tieLoser =
        User.builder()
            .username("tieLoser")
            .overwatch(OverwatchProfile.builder()
                .playerId("Tie-2").winrate(55.0).gamesWon(10).build())
            .build();
    User unattached = User.builder().username("nope").build();

    when(userRepository.findAll()).thenReturn(List.of(tieLoser, unattached, tieWinner, best));

    List<OverwatchProfileDto> leaderboard = overwatchService.getLeaderboard();

    assertEquals(
        List.of("best", "tieWinner", "tieLoser"),
        leaderboard.stream().map(OverwatchProfileDto::getUsername).toList());
  }
}
