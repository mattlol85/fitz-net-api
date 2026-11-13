package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import org.fitznet.fitznetapi.dto.requests.PresetRequestDto;
import org.fitznet.fitznetapi.dto.responses.DeleteResponseDto;
import org.fitznet.fitznetapi.dto.responses.PresetDto;
import org.fitznet.fitznetapi.dto.responses.PresetListDto;
import org.fitznet.fitznetapi.service.ai.PresetService;
import org.fitznet.fitznetapi.service.ai.PresetViewer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Matt-GPT presets (contract section 9). Visibility is by permission: a private preset is its
 * creator's alone (a 404 for everyone else, ADMIN included); a shared one is visible by
 * {@code requiredPermission} and to ADMIN, and only ADMIN may create, change or delete it. Every
 * endpoint needs {@code AI_USER} or {@code ADMIN}; the gRPC {@code AiChatService} calls these same
 * methods.
 */
@RestController
@RequestMapping("/ai/presets")
@PreAuthorize("hasAnyAuthority('PERM_AI_USER','PERM_ADMIN')")
public class AiPresetController {

  private final PresetService presetService;

  public AiPresetController(PresetService presetService) {
    this.presetService = presetService;
  }

  @GetMapping
  public PresetListDto listPresets() {
    return presetService.list(PresetViewer.current());
  }

  @PostMapping
  public PresetDto createPreset(@RequestBody @Valid PresetRequestDto request) {
    return presetService.create(PresetViewer.current(), request);
  }

  @PutMapping("/{id}")
  public PresetDto updatePreset(
      @PathVariable String id, @RequestBody @Valid PresetRequestDto request) {
    return presetService.update(PresetViewer.current(), id, request);
  }

  @DeleteMapping("/{id}")
  public DeleteResponseDto deletePreset(@PathVariable String id) {
    return presetService.delete(PresetViewer.current(), id);
  }
}
