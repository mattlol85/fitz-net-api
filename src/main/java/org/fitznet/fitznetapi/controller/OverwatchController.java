package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import java.util.List;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSearchResultDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchProfileDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchProfileRequestDto;
import org.fitznet.fitznetapi.service.OverwatchService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/overwatch")
public class OverwatchController {

  private final OverwatchService overwatchService;

  public OverwatchController(OverwatchService overwatchService) {
    this.overwatchService = overwatchService;
  }

  @GetMapping("/search")
  public List<OverwatchPlayerSearchResultDto> search(@RequestParam String name) {
    return overwatchService.searchPlayers(name);
  }

  @PostMapping("/profile")
  public OverwatchProfileDto createProfile(
      @RequestBody @Valid OverwatchProfileRequestDto request, Authentication authentication) {
    return attachProfile(request, authentication);
  }

  @PutMapping("/profile")
  public OverwatchProfileDto updateProfile(
      @RequestBody @Valid OverwatchProfileRequestDto request, Authentication authentication) {
    return attachProfile(request, authentication);
  }

  @GetMapping("/me")
  public OverwatchProfileDto me(Authentication authentication) {
    return overwatchService.getProfile(authentication.getName());
  }

  @GetMapping("/leaderboard")
  public List<OverwatchProfileDto> leaderboard() {
    return overwatchService.getLeaderboard();
  }

  private OverwatchProfileDto attachProfile(
      OverwatchProfileRequestDto request, Authentication authentication) {
    return overwatchService.attachProfile(
        authentication.getName(), request.getPlayerId(), request.getGamemode(), request.getPlatform());
  }
}
