package org.fitznet.fitznetapi.dto.responses;

import java.util.List;

/** {@code GET /ai/presets}: the presets visible to the caller. */
public record PresetListDto(List<PresetDto> items) {}
