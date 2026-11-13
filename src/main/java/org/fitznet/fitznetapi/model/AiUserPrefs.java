package org.fitznet.fitznetapi.model;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Matt-GPT preferences, one document per user; {@code _id} is the canonical lowercase username. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Document("ai_user_prefs")
public class AiUserPrefs {

  @Id String username;

  ModelTarget lastTarget;
  String lastPresetId;
  Instant updatedAt;
}
