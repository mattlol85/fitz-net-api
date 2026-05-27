package org.fitznet.fitznetapi.dto.liveboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BoardMessageDto {
  private String id;
  private String username;
  @JsonProperty("xRatio") private double xRatio;
  @JsonProperty("yRatio") private double yRatio;
  private String content;
  @JsonProperty("postedAt") private Instant postedAt;

  public static BoardMessageDto create(String username, double xRatio, double yRatio, String content) {
    return BoardMessageDto.builder()
        .id(UUID.randomUUID().toString())
        .username(username)
        .xRatio(xRatio)
        .yRatio(yRatio)
        .content(content)
        .postedAt(Instant.now())
        .build();
  }
}

