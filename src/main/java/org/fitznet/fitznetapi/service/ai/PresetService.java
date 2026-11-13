package org.fitznet.fitznetapi.service.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.fitznet.fitznetapi.dto.requests.PresetRequestDto;
import org.fitznet.fitznetapi.dto.responses.DeleteResponseDto;
import org.fitznet.fitznetapi.dto.responses.PresetDto;
import org.fitznet.fitznetapi.dto.responses.PresetListDto;
import org.fitznet.fitznetapi.model.AiPreset;
import org.fitznet.fitznetapi.repository.AiPresetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Presets (contract section 9). Visibility is by permission:
 *
 * <ul>
 *   <li>{@code private}: visible to and editable by its creator only; anyone else (ADMIN included)
 *       gets a 404.
 *   <li>{@code shared}: visible to callers holding {@code requiredPermission} (everyone with AI
 *       access when it is null) and to ADMIN; only ADMIN may create, change or delete one, or make
 *       a preset shared (403).
 * </ul>
 *
 * Prompts and descriptions are never logged.
 */
@Service
public class PresetService {

  private static final Logger logger = LoggerFactory.getLogger(PresetService.class);

  private final AiPresetRepository presets;

  public PresetService(AiPresetRepository presets) {
    this.presets = presets;
  }

  public PresetListDto list(PresetViewer viewer) {
    List<AiPreset> visible = new ArrayList<>(presets.findByOwnerUsernameAndVisibility(viewer.username(), AiPreset.PRIVATE));
    presets.findByVisibility(AiPreset.SHARED).stream()
        .filter(p -> visible(p, viewer))
        .forEach(visible::add);
    visible.sort(
        Comparator.comparing(AiPreset::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(AiPreset::getId));
    return new PresetListDto(visible.stream().map(p -> toDto(p, viewer)).toList());
  }

  public PresetDto create(PresetViewer viewer, PresetRequestDto request) {
    String visibility = visibilityOf(request);
    if (AiPreset.SHARED.equals(visibility) && !viewer.admin()) {
      throw forbidden();
    }
    Instant now = Instant.now();
    AiPreset preset = AiPreset.builder().ownerUsername(viewer.username()).createdAt(now).build();
    fill(preset, request, visibility, now);
    AiPreset saved = presets.save(preset);
    logger.info("Created {} preset {}", saved.getVisibility(), saved.getId());
    return toDto(saved, viewer);
  }

  /** PUT: replaces every field of the preset. */
  public PresetDto update(PresetViewer viewer, String id, PresetRequestDto request) {
    AiPreset preset = editable(viewer, id);
    String visibility = visibilityOf(request);
    if (AiPreset.SHARED.equals(visibility) && !viewer.admin()) {
      throw forbidden();
    }
    fill(preset, request, visibility, Instant.now());
    return toDto(presets.save(preset), viewer);
  }

  public DeleteResponseDto delete(PresetViewer viewer, String id) {
    AiPreset preset = editable(viewer, id);
    presets.deleteById(preset.getId());
    logger.info("Deleted preset {}", id);
    return new DeleteResponseDto(true);
  }

  /**
   * The preset if it exists and the user may see it right now (a deleted or no longer visible
   * preset is simply absent, so a turn treats it as none).
   */
  public Optional<AiPreset> findVisible(String username, String presetId) {
    if (presetId == null || presetId.isBlank()) {
      return Optional.empty();
    }
    PresetViewer viewer = PresetViewer.of(username);
    return presets.findById(presetId).filter(p -> visible(p, viewer));
  }

  // ---- rules -------------------------------------------------------------------------------

  static boolean visible(AiPreset preset, PresetViewer viewer) {
    if (AiPreset.SHARED.equals(preset.getVisibility())) {
      return viewer.admin()
          || preset.getRequiredPermission() == null
          || viewer.permissions().contains(preset.getRequiredPermission());
    }
    return viewer.username().equals(preset.getOwnerUsername());
  }

  static boolean editable(AiPreset preset, PresetViewer viewer) {
    return AiPreset.SHARED.equals(preset.getVisibility())
        ? viewer.admin()
        : viewer.username().equals(preset.getOwnerUsername());
  }

  /** The preset when the caller may see it (404 otherwise) and edit it (403 otherwise). */
  private AiPreset editable(PresetViewer viewer, String id) {
    AiPreset preset =
        presets
            .findById(id)
            .filter(p -> visible(p, viewer))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Preset not found"));
    if (!editable(preset, viewer)) {
      throw forbidden();
    }
    return preset;
  }

  private static ResponseStatusException forbidden() {
    return new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an admin can manage shared presets");
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static String visibilityOf(PresetRequestDto request) {
    return request.getVisibility() == null || request.getVisibility().isBlank()
        ? AiPreset.PRIVATE
        : request.getVisibility();
  }

  /** Applies the request after the cross-field checks the annotations cannot express. */
  private static void fill(AiPreset preset, PresetRequestDto request, String visibility, Instant now) {
    String name = request.getName() == null ? "" : request.getName().trim();
    if (name.isEmpty()) {
      throw badRequest("name must not be blank");
    }
    String model = blankToNull(request.getModel());
    String nodeId = blankToNull(request.getNodeId());
    String requiredPermission = blankToNull(request.getRequiredPermission());
    if (nodeId != null && model == null) {
      throw badRequest("nodeId is only meaningful together with a model");
    }
    if (requiredPermission != null && !AiPreset.SHARED.equals(visibility)) {
      throw badRequest("requiredPermission only applies to a shared preset");
    }
    preset.setName(name);
    preset.setDescription(blankToNull(request.getDescription()));
    preset.setModel(model);
    preset.setNodeId(nodeId);
    preset.setSystemPrompt(blankToNull(request.getSystemPrompt()));
    preset.setTemperature(request.getTemperature());
    preset.setNumCtx(request.getNumCtx());
    preset.setVisibility(visibility);
    preset.setRequiredPermission(requiredPermission);
    preset.setUpdatedAt(now);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static PresetDto toDto(AiPreset p, PresetViewer viewer) {
    return new PresetDto(
        p.getId(),
        p.getName(),
        p.getDescription(),
        p.getModel(),
        p.getNodeId(),
        p.getSystemPrompt(),
        p.getTemperature(),
        p.getNumCtx(),
        p.getVisibility(),
        p.getRequiredPermission(),
        p.getOwnerUsername(),
        editable(p, viewer),
        p.getCreatedAt(),
        p.getUpdatedAt());
  }
}
