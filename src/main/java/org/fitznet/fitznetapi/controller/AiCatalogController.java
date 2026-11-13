package org.fitznet.fitznetapi.controller;

import jakarta.validation.Valid;
import org.fitznet.fitznetapi.dto.requests.UpdatePreferencesRequestDto;
import org.fitznet.fitznetapi.dto.requests.WarmUpRequestDto;
import org.fitznet.fitznetapi.dto.responses.ChatPreferencesDto;
import org.fitznet.fitznetapi.dto.responses.ModelCatalogDto;
import org.fitznet.fitznetapi.dto.responses.WarmUpResponseDto;
import org.fitznet.fitznetapi.service.ai.AiPreferencesService;
import org.fitznet.fitznetapi.service.ai.ModelCatalogService;
import org.fitznet.fitznetapi.service.ai.WarmUpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Matt-GPT v2 node/model layer: the picker catalogue, warm-up and per-user preferences. Every
 * endpoint needs {@code AI_USER} or {@code ADMIN}; the gRPC {@code AiChatService} calls these same
 * methods so the checks are shared.
 */
@RestController
@RequestMapping("/ai")
@PreAuthorize("hasAnyAuthority('PERM_AI_USER','PERM_ADMIN')")
public class AiCatalogController {

  private static final Logger log = LoggerFactory.getLogger(AiCatalogController.class);

  private final ModelCatalogService catalogService;
  private final WarmUpService warmUpService;
  private final AiPreferencesService preferencesService;

  public AiCatalogController(
      ModelCatalogService catalogService,
      WarmUpService warmUpService,
      AiPreferencesService preferencesService) {
    this.catalogService = catalogService;
    this.warmUpService = warmUpService;
    this.preferencesService = preferencesService;
  }

  @GetMapping("/models")
  public ModelCatalogDto listModels(@RequestParam(defaultValue = "false") boolean refresh) {
    log.debug("Request at /ai/models - refresh: {}", refresh);
    return catalogService.toDto(catalogService.catalog(refresh));
  }

  @PostMapping("/warmup")
  public WarmUpResponseDto warmUp(@RequestBody @Valid WarmUpRequestDto request) {
    log.info("Request at /ai/warmup - model: {}, node: {}", request.getModel(), request.getNodeId());
    return warmUpService.warmUp(request.getModel(), request.getNodeId());
  }

  @GetMapping("/preferences")
  public ChatPreferencesDto getPreferences() {
    return preferencesService.get(currentUsername());
  }

  @PutMapping("/preferences")
  public ChatPreferencesDto updatePreferences(
      @RequestBody @Valid UpdatePreferencesRequestDto request) {
    return preferencesService.update(currentUsername(), request);
  }

  private static String currentUsername() {
    return SecurityContextHolder.getContext().getAuthentication().getName();
  }
}
