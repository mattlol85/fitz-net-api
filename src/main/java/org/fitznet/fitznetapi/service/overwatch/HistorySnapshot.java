package org.fitznet.fitznetapi.service.overwatch;

import java.util.List;
import lombok.Value;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchSeasonHistoryPointDto;

@Value
public class HistorySnapshot {
  String currentSeason;
  List<OverwatchSeasonHistoryPointDto> dpsHistory;
  List<OverwatchSeasonHistoryPointDto> tankHistory;
  List<OverwatchSeasonHistoryPointDto> healsHistory;
}
