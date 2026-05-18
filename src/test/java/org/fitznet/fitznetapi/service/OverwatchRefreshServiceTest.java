package org.fitznet.fitznetapi.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OverwatchRefreshServiceTest {

  @Mock private OverwatchClient overwatchClient;
  @Mock private UserRepository userRepository;
  @Mock private OverwatchRatingSnapshotRepository snapshotRepository;

  private AutoCloseable mocks;
  private OverwatchRefreshService refreshService;

  @BeforeEach
  void setUp() throws Exception {
    mocks = MockitoAnnotations.openMocks(this);
    refreshService = new OverwatchRefreshService(overwatchClient, userRepository, snapshotRepository);

    // Inject @Value fields via reflection
    var interRequestDelayField = OverwatchRefreshService.class.getDeclaredField("interRequestDelayMs");
    interRequestDelayField.setAccessible(true);
    interRequestDelayField.set(refreshService, 0L);

    var cooldownField = OverwatchRefreshService.class.getDeclaredField("cooldownMinutes");
    cooldownField.setAccessible(true);
    cooldownField.set(refreshService, 20L);

    var seasonField = OverwatchRefreshService.class.getDeclaredField("currentSeason");
    seasonField.setAccessible(true);
    seasonField.set(refreshService, "Season 16");
  }

  @AfterEach
  void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }

  // ---- saveSnapshotAndUpdatePeaks ----

  @Test
  void saveSnapshotShouldPersistSnapshotForCurrentSeason() {
    User user = User.builder().id("u1").username("matt").build();
    CompetitiveRatings ratings = new CompetitiveRatings(2800, 2600, 2900, null, null, null, null);

    when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    refreshService.saveSnapshotAndUpdatePeaks(user, ratings);

    ArgumentCaptor<OverwatchRatingSnapshot> captor = ArgumentCaptor.forClass(OverwatchRatingSnapshot.class);
    verify(snapshotRepository).save(captor.capture());
    OverwatchRatingSnapshot saved = captor.getValue();

    assertEquals("u1", saved.getUserId());
    assertEquals("Season 16", saved.getSeason());
    assertEquals(2800, saved.getDpsRating());
    assertEquals(2600, saved.getTankRating());
    assertEquals(2900, saved.getHealsRating());
    assertNotNull(saved.getRecordedAt());
  }

  @Test
  void saveSnapshotShouldUpdatePeakWhenCurrentRatingExceedsPrevious() {
    User user = User.builder().id("u1").username("matt")
        .overwatchDpsPeakRating(2700)
        .overwatchTankPeakRating(2600)
        .overwatchHealsPeakRating(2900)
        .build();
    CompetitiveRatings ratings = new CompetitiveRatings(2800, 2550, 2950, null, null, null, null);

    when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    when(userRepository.save(userCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

    refreshService.saveSnapshotAndUpdatePeaks(user, ratings);

    User savedUser = userCaptor.getValue();
    assertEquals(2800, savedUser.getOverwatchDpsPeakRating()); // updated (2800 > 2700)
    assertEquals(2600, savedUser.getOverwatchTankPeakRating()); // unchanged (2550 < 2600)
    assertEquals(2950, savedUser.getOverwatchHealsPeakRating()); // updated (2950 > 2900)
  }

  @Test
  void saveSnapshotShouldSetPeakWhenNoPreviousPeakExists() {
    User user = User.builder().id("u1").username("matt").build();
    CompetitiveRatings ratings = new CompetitiveRatings(2800, 2600, null, null, null, null, null);

    when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    when(userRepository.save(userCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

    refreshService.saveSnapshotAndUpdatePeaks(user, ratings);

    User savedUser = userCaptor.getValue();
    assertEquals(2800, savedUser.getOverwatchDpsPeakRating());
    assertEquals(2600, savedUser.getOverwatchTankPeakRating());
    assertNull(savedUser.getOverwatchHealsPeakRating()); // null rating = no peak update
  }

  // ---- cron job cooldown ----

  @Test
  void cronShouldSkipUsersRefreshedWithinCooldownWindow() {
    User recentUser = User.builder()
        .id("u1").username("recent")
        .overwatchPlayerId("Recent-1")
        .overwatchLastUpdatedAt(Instant.now().minus(5, ChronoUnit.MINUTES))
        .build();
    User staleUser = User.builder()
        .id("u2").username("stale")
        .overwatchPlayerId("Stale-1")
        .overwatchLastUpdatedAt(Instant.now().minus(30, ChronoUnit.MINUTES))
        .build();

    OverfastPlayerCompleteDto emptyComplete = new OverfastPlayerCompleteDto();
    when(userRepository.findAll()).thenReturn(List.of(recentUser, staleUser));
    when(overwatchClient.getCompletePlayerInfo("Stale-1")).thenReturn(emptyComplete);
    when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    refreshService.refreshAllLinkedUsers();

    verify(overwatchClient, never()).getCompletePlayerInfo("Recent-1");
    verify(overwatchClient, times(1)).getCompletePlayerInfo("Stale-1");
  }

  @Test
  void cronShouldContinueAfterSingleUserFailure() {
    User failUser = User.builder().id("u1").username("fail").overwatchPlayerId("Fail-1").build();
    User okUser = User.builder().id("u2").username("ok").overwatchPlayerId("Ok-1").build();

    when(userRepository.findAll()).thenReturn(List.of(failUser, okUser));
    when(overwatchClient.getCompletePlayerInfo("Fail-1"))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OverFast down"));
    when(overwatchClient.getCompletePlayerInfo("Ok-1")).thenReturn(new OverfastPlayerCompleteDto());
    when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Should not throw
    refreshService.refreshAllLinkedUsers();

    verify(overwatchClient, times(1)).getCompletePlayerInfo("Fail-1");
    verify(overwatchClient, times(1)).getCompletePlayerInfo("Ok-1");
  }

  // ---- extractCompetitiveRatings ----

  @Test
  void extractCompetitiveRatingsShouldMapDivisionAndTierToSr() {
    OverfastRoleRankDto dpsRank = new OverfastRoleRankDto();
    dpsRank.setDivision("diamond");
    dpsRank.setTier(2);

    OverfastRoleRankDto tankRank = new OverfastRoleRankDto();
    tankRank.setDivision("platinum");
    tankRank.setTier(1);

    OverfastPlatformRanksDto platform = new OverfastPlatformRanksDto();
    platform.setDamage(dpsRank);
    platform.setTank(tankRank);
    platform.setSupport(null);

    OverfastCompetitiveDto competitive = new OverfastCompetitiveDto();
    competitive.setPc(platform);

    OverfastSummaryDto summaryDto = new OverfastSummaryDto();
    summaryDto.setCompetitive(competitive);

    OverfastPlayerCompleteDto playerComplete = new OverfastPlayerCompleteDto();
    playerComplete.setSummary(summaryDto);

    CompetitiveRatings ratings = OverwatchRefreshService.extractCompetitiveRatings(playerComplete);

    // diamond tier 2 = 2000 + (5-2)*100 = 2300
    assertEquals(2300, ratings.getDpsRating());
    // platinum tier 1 = 1500 + (5-1)*100 = 1900
    assertEquals(1900, ratings.getTankRating());
    assertNull(ratings.getHealsRating());
  }

  @Test
  void extractCompetitiveRatingsShouldReturnNullsWhenNoCompetitiveData() {
    CompetitiveRatings ratings = OverwatchRefreshService.extractCompetitiveRatings(null);
    assertNull(ratings.getDpsRating());
    assertNull(ratings.getTankRating());
    assertNull(ratings.getHealsRating());
  }

  // ---- isOverfastRateLimited ----

  @Test
  void isOverfastRateLimitedShouldDetect503() {
    assertTrue(OverwatchRefreshService.isOverfastRateLimited(
        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)));
    assertFalse(OverwatchRefreshService.isOverfastRateLimited(
        new ResponseStatusException(HttpStatus.BAD_GATEWAY)));
    assertFalse(OverwatchRefreshService.isOverfastRateLimited(new RuntimeException("Timeout")));
  }
}
