package org.fitznet.fitznetapi.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSearchResultDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchProfileDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchProfileRequestDto;
import org.fitznet.fitznetapi.service.OverwatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class OverwatchControllerTest {

  @Mock private OverwatchService overwatchService;

  private AutoCloseable mocks;
  private OverwatchController overwatchController;

  @BeforeEach
  void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    overwatchController = new OverwatchController(overwatchService);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }

  @Test
  void searchShouldReturnPlayerResults() {
    OverwatchPlayerSearchResultDto player = new OverwatchPlayerSearchResultDto();
    player.setPlayerId("Matt-1234");
    player.setName("Matt#1234");

    when(overwatchService.searchPlayers("Matt")).thenReturn(List.of(player));

    List<OverwatchPlayerSearchResultDto> results = overwatchController.search("Matt");

    assertEquals(1, results.size());
    assertEquals("Matt-1234", results.getFirst().getPlayerId());
  }

  @Test
  void updateProfileShouldAttachToAuthenticatedUser() {
    OverwatchProfileRequestDto request = new OverwatchProfileRequestDto();
    request.setPlayerId("Matt-1234");
    request.setGamemode("competitive");
    request.setPlatform("pc");
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("matt", null, null);
    OverwatchProfileDto profile =
        OverwatchProfileDto.builder().username("matt").playerId("Matt-1234").build();

    when(overwatchService.attachProfile("matt", "Matt-1234", "competitive", "pc"))
        .thenReturn(profile);

    OverwatchProfileDto result = overwatchController.updateProfile(request, authentication);

    assertEquals("Matt-1234", result.getPlayerId());
    verify(overwatchService, times(1)).attachProfile("matt", "Matt-1234", "competitive", "pc");
  }
}
