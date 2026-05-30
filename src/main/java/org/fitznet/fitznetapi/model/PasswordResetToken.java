package org.fitznet.fitznetapi.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "password_reset_tokens")
public class PasswordResetToken {

  @Id
  private String id;

  @Indexed
  private String email;

  private String token;

  private LocalDateTime expiresAt;

  private boolean used;
}
