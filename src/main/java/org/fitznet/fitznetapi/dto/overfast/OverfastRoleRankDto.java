package org.fitznet.fitznetapi.dto.overfast;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverfastRoleRankDto {
  private String division;
  private Integer tier;

  @JsonAlias("role_icon")
  private String roleIcon;

  @JsonAlias("rank_icon")
  private String rankIcon;
}

