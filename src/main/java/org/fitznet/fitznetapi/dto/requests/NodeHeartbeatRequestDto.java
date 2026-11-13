package org.fitznet.fitznetapi.dto.requests;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

  /** Optional GPU memory total, MiB (nvidia-smi memory.total). Absent leaves the stored value alone. */
  @Min(0)
  @Max(MAX_VRAM_MB)
  Integer vramTotalMb;

  /** Optional GPU memory in use by everything on the node, MiB. Absent leaves the stored value alone. */
  @Min(0)
  @Max(MAX_VRAM_MB)
  Integer vramUsedMb;

  static final int MAX_VRAM_MB = 4_194_304;

  public NodeHeartbeatRequestDto(String status, List<String> models, String address) {
    this.status = status;
    this.models = models;
    this.address = address;
  }
}
