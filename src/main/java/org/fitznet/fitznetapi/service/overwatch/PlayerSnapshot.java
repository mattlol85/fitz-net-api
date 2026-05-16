package org.fitznet.fitznetapi.service.overwatch;

import lombok.Value;

@Value
public class PlayerSnapshot {
  String playerId;
  String battleTag;
  String displayName;
  String avatarUrl;
}
