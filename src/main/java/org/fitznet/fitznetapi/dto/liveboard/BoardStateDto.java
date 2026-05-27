package org.fitznet.fitznetapi.dto.liveboard;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BoardStateDto {
  private List<BoardMessageDto> messages;
}

