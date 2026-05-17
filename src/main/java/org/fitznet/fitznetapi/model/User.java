package org.fitznet.fitznetapi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Document("users")
@ToString(exclude = "password")
public class User {

  @Id String id;

  String username;
  @JsonIgnore String password;
  String email;

  String overwatchPlayerId;
  String overwatchBattleTag;
  String overwatchDisplayName;
  String overwatchAvatarUrl;
  Instant overwatchLastUpdatedAt;
  Integer overwatchGamesWon;
  Integer overwatchGamesPlayed;
  Double overwatchWinrate;
  Double overwatchKda;
  Integer overwatchEliminations;
  Integer overwatchDeaths;
  Integer overwatchDamage;
  Integer overwatchHealing;

  // Role-specific competitive ratings
  Integer overwatchDpsRating;
  Integer overwatchTankRating;
  Integer overwatchHealsRating;

  // All-time peak competitive ratings
  Integer overwatchDpsPeakRating;
  Integer overwatchTankPeakRating;
  Integer overwatchHealsPeakRating;
}
