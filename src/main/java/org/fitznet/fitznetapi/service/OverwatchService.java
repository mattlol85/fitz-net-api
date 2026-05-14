package org.fitznet.fitznetapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSearchResultDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchPlayerSummaryDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchProfileDto;
import org.fitznet.fitznetapi.dto.overwatch.OverwatchStatsSnapshotDto;
import org.fitznet.fitznetapi.model.User;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
public class OverwatchService {

  private final OverwatchClient overwatchClient;
  private final UserRepository userRepository;

  public OverwatchService(OverwatchClient overwatchClient, UserRepository userRepository) {
    this.overwatchClient = overwatchClient;
    this.userRepository = userRepository;
  }

  public List<OverwatchPlayerSearchResultDto> searchPlayers(String name) {
    if (name == null || name.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Player name is required");
    }

    return overwatchClient.searchPlayers(name);
  }

  public OverwatchProfileDto attachProfile(
      String username, String playerId, String gamemode, String platform) {
    if (playerId == null || playerId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "playerId is required");
    }

    User user = findUser(username);
    OverwatchPlayerSummaryDto summary = overwatchClient.getPlayerSummary(playerId);
    OverwatchStatsSnapshotDto stats =
        extractStats(overwatchClient.getStatsSummary(playerId, gamemode, platform));

    user.setOverwatchPlayerId(resolvePlayerId(summary, playerId));
    user.setOverwatchDisplayName(summary.getName());
    user.setOverwatchAvatarUrl(summary.getAvatar());
    user.setOverwatchLastUpdatedAt(Instant.now());
    user.setOverwatchGamesWon(stats.getGamesWon());
    user.setOverwatchGamesPlayed(stats.getGamesPlayed());
    user.setOverwatchWinrate(stats.getWinrate());
    user.setOverwatchKda(stats.getKda());
    user.setOverwatchEliminations(stats.getEliminations());
    user.setOverwatchDeaths(stats.getDeaths());
    user.setOverwatchDamage(stats.getDamage());
    user.setOverwatchHealing(stats.getHealing());

    User saved = userRepository.save(user);
    log.info("Attached Overwatch player {} to user {}", saved.getOverwatchPlayerId(), username);
    return toProfileDto(saved);
  }

  public OverwatchProfileDto getProfile(String username) {
    return toProfileDto(findUser(username));
  }

  public List<OverwatchProfileDto> getLeaderboard() {
    return userRepository.findAll().stream()
        .filter(user -> user.getOverwatchPlayerId() != null)
        .sorted(
            Comparator.comparing(
                    OverwatchService::leaderboardScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(
                    user -> defaultInteger(user.getOverwatchGamesWon()),
                    Comparator.reverseOrder())
                .thenComparing(User::getUsername))
        .map(this::toProfileDto)
        .toList();
  }

  private static Double leaderboardScore(User user) {
    return user.getOverwatchWinrate();
  }

  private static Integer defaultInteger(Integer value) {
    return value == null ? 0 : value;
  }

  private User findUser(String username) {
    User user = userRepository.findByUsername(username);
    if (user == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }
    return user;
  }

  private OverwatchProfileDto toProfileDto(User user) {
    return OverwatchProfileDto.builder()
        .username(user.getUsername())
        .playerId(user.getOverwatchPlayerId())
        .displayName(user.getOverwatchDisplayName())
        .avatarUrl(user.getOverwatchAvatarUrl())
        .lastUpdatedAt(user.getOverwatchLastUpdatedAt())
        .gamesWon(user.getOverwatchGamesWon())
        .gamesPlayed(user.getOverwatchGamesPlayed())
        .winrate(user.getOverwatchWinrate())
        .kda(user.getOverwatchKda())
        .eliminations(user.getOverwatchEliminations())
        .deaths(user.getOverwatchDeaths())
        .damage(user.getOverwatchDamage())
        .healing(user.getOverwatchHealing())
        .build();
  }

  private static String resolvePlayerId(OverwatchPlayerSummaryDto summary, String requestedPlayerId) {
    return summary.getPlayerId() == null || summary.getPlayerId().isBlank()
        ? requestedPlayerId
        : summary.getPlayerId();
  }

  private static OverwatchStatsSnapshotDto extractStats(JsonNode statsRoot) {
    Integer gamesWon = firstInt(statsRoot, "gameswon", "games_won", "games won");
    Integer gamesPlayed = firstInt(statsRoot, "gamesplayed", "games_played", "games played");
    Integer eliminations = firstInt(statsRoot, "eliminations", "elims");
    Integer deaths = firstInt(statsRoot, "deaths");
    Integer damage = firstInt(statsRoot, "damage", "hero_damage_done", "hero damage done");
    Integer healing = firstInt(statsRoot, "healing", "healing_done", "healing done");
    Double winrate = firstDouble(statsRoot, "winrate", "win_rate", "win rate");
    Double kda = firstDouble(statsRoot, "kda");

    if (winrate == null && gamesWon != null && gamesPlayed != null && gamesPlayed > 0) {
      winrate = (gamesWon * 100.0) / gamesPlayed;
    }

    if (kda == null && eliminations != null && deaths != null) {
      kda = eliminations / (double) Math.max(deaths, 1);
    }

    return OverwatchStatsSnapshotDto.builder()
        .gamesWon(gamesWon)
        .gamesPlayed(gamesPlayed)
        .winrate(winrate)
        .kda(kda)
        .eliminations(eliminations)
        .deaths(deaths)
        .damage(damage)
        .healing(healing)
        .build();
  }

  private static Integer firstInt(JsonNode root, String... aliases) {
    Double value = firstDouble(root, aliases);
    return value == null ? null : value.intValue();
  }

  private static Double firstDouble(JsonNode root, String... aliases) {
    if (root == null) {
      return null;
    }

    if (root.isObject()) {
      Double labeledValue = matchingLabeledValue(root, aliases);
      if (labeledValue != null) {
        return labeledValue;
      }

      var fields = root.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        if (matches(field.getKey(), aliases)) {
          Double value = numericValue(field.getValue());
          if (value != null) {
            return value;
          }
        }
      }

      fields = root.fields();
      while (fields.hasNext()) {
        Double nestedValue = firstDouble(fields.next().getValue(), aliases);
        if (nestedValue != null) {
          return nestedValue;
        }
      }
    } else if (root.isArray()) {
      for (JsonNode item : root) {
        Double nestedValue = firstDouble(item, aliases);
        if (nestedValue != null) {
          return nestedValue;
        }
      }
    }

    return null;
  }

  private static Double matchingLabeledValue(JsonNode root, String[] aliases) {
    String label = textValue(root, "key", "label", "name", "title");
    if (label == null || !matches(label, aliases)) {
      return null;
    }

    for (String valueField : List.of("value", "amount", "total")) {
      Double value = numericValue(root.get(valueField));
      if (value != null) {
        return value;
      }
    }

    return null;
  }

  private static String textValue(JsonNode root, String... fieldNames) {
    for (String fieldName : fieldNames) {
      JsonNode value = root.get(fieldName);
      if (value != null && value.isTextual()) {
        return value.asText();
      }
    }
    return null;
  }

  private static Double numericValue(JsonNode value) {
    if (value == null || value.isNull()) {
      return null;
    }

    if (value.isNumber()) {
      return value.asDouble();
    }

    if (value.isTextual()) {
      try {
        return Double.parseDouble(value.asText().replace("%", "").trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }

    return null;
  }

  private static boolean matches(String fieldName, String[] aliases) {
    String normalizedField = normalize(fieldName);
    for (String alias : aliases) {
      if (normalizedField.equals(normalize(alias))) {
        return true;
      }
    }
    return false;
  }

  private static String normalize(String value) {
    return value == null
        ? ""
        : value.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
  }
}
