package org.fitznet.fitznetapi.service.ai;

import java.time.Instant;
import org.fitznet.fitznetapi.dto.ModelTargetDto;
import org.fitznet.fitznetapi.dto.requests.UpdatePreferencesRequestDto;
import org.fitznet.fitznetapi.dto.responses.ChatPreferencesDto;
import org.fitznet.fitznetapi.model.AiUserPrefs;
import org.fitznet.fitznetapi.model.ModelTarget;
import org.fitznet.fitznetapi.repository.AiUserPrefsRepository;
import org.springframework.stereotype.Service;

/**
 * Per-user Matt-GPT preferences (contract section 11): the last picked target and preset. The
 * user key is always the canonical lowercase username from the security context, so one user can
 * never read or write another's document.
 */
@Service
public class AiPreferencesService {

  private final AiUserPrefsRepository repository;

  public AiPreferencesService(AiUserPrefsRepository repository) {
    this.repository = repository;
  }

  public ChatPreferencesDto get(String username) {
    return repository
        .findById(username)
        .map(AiPreferencesService::toDto)
        .orElseGet(() -> new ChatPreferencesDto(null, null, null));
  }

  /** Absent fields are left alone; present ones (including explicit null) replace the stored value. */
  public ChatPreferencesDto update(String username, UpdatePreferencesRequestDto request) {
    AiUserPrefs prefs = load(username);
    if (request.isLastTargetPresent()) {
      ModelTargetDto target = request.getLastTarget();
      prefs.setLastTarget(target == null ? null : new ModelTarget(target.getModel(), blankToNull(target.getNodeId())));
    }
    if (request.isLastPresetIdPresent()) {
      prefs.setLastPresetId(blankToNull(request.getLastPresetId()));
    }
    prefs.setUpdatedAt(Instant.now());
    return toDto(repository.save(prefs));
  }

  /**
   * Called by the turn and conversation-PATCH paths whenever a request carries a target, so the
   * next new chat starts where the user last was.
   */
  public void setLastTarget(String username, ModelTarget target) {
    AiUserPrefs prefs = load(username);
    prefs.setLastTarget(new ModelTarget(target.model(), blankToNull(target.nodeId())));
    prefs.setUpdatedAt(Instant.now());
    repository.save(prefs);
  }

  private AiUserPrefs load(String username) {
    return repository
        .findById(username)
        .orElseGet(() -> AiUserPrefs.builder().username(username).build());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static ChatPreferencesDto toDto(AiUserPrefs prefs) {
    ModelTarget target = prefs.getLastTarget();
    return new ChatPreferencesDto(
        target == null ? null : new ModelTargetDto(target.model(), target.nodeId()),
        prefs.getLastPresetId(),
        prefs.getUpdatedAt());
  }
}
