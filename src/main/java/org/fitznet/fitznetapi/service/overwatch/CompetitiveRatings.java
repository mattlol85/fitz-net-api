package org.fitznet.fitznetapi.service.overwatch;

import java.util.List;
import lombok.Value;

@Value
public class CompetitiveRatings {
  Integer dpsRating;
  Integer tankRating;
  Integer healsRating;
  String dpsRankIcon;
  String tankRankIcon;
  String healsRankIcon;
  List<HeroStats> heroStats;
}
