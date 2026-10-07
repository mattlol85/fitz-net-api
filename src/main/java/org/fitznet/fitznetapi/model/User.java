package org.fitznet.fitznetapi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import java.util.HashSet;
import java.util.Set;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.AccessLevel;
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

  String boardColor;

  /** Granted {@link Permission} names. Missing in legacy documents, so treat null as empty. */
  @Builder.Default Set<String> permissions = new HashSet<>();
}
