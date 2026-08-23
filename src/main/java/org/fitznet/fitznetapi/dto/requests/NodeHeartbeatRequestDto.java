package org.fitznet.fitznetapi.dto.requests;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodeHeartbeatRequestDto {
  String status;
  List<String> models;
  String address;
}
