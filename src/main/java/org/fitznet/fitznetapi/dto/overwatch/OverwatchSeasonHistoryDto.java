package org.fitznet.fitznetapi.dto.overwatch;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OverwatchSeasonHistoryDto {
  String username;
  String playerId;
  String battleTag;
  String displayName;
  String avatarUrl;
  String currentSeason;
  Integer dpsRating;
  Integer tankRating;
  Integer healsRating;
  Integer dpsPeakRating;
  Integer tankPeakRating;
  Integer healsPeakRating;
  String dpsRankIcon;
  String tankRankIcon;
  String healsRankIcon;
  List<OverwatchSeasonHistoryPointDto> dpsHistory;
  List<OverwatchSeasonHistoryPointDto> tankHistory;
  List<OverwatchSeasonHistoryPointDto> healsHistory;
  List<OverwatchSeasonHistoryPointDto> dpsSeasonHistory;
  List<OverwatchSeasonHistoryPointDto> tankSeasonHistory;
  List<OverwatchSeasonHistoryPointDto> healsSeasonHistory;
  List<OverwatchRankedMatchDto> rankedMatches;
}




