package org.fitznet.fitznetapi.dto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.fitznet.fitznetapi.dto.liveboard.BoardMessageDto;
import org.fitznet.fitznetapi.dto.liveboard.CursorMoveDto;
import org.fitznet.fitznetapi.dto.responses.LoginResponseDto;
import org.fitznet.fitznetapi.dto.responses.UpdateProfileResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that @JsonProperty annotations preserve camelCase field names
 * even when Jackson is configured with LOWER_CASE naming strategy.
 *
 * Root cause: spring-boot-starter-data-rest applies LOWER_CASE naming globally.
 * Without @JsonProperty, CursorMoveDto.xRatio serialises as "xratio" and
 * incoming "xRatio" from the client deserialises as 0.0, pinning all cursors
 * to the top-left corner (0,0) of the Live Board.
 */
class JsonPropertyNamingTest {

  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    // Reproduce the LOWER_CASE naming strategy imposed by spring-boot-starter-data-rest
    mapper = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CASE)
        .registerModule(new JavaTimeModule());
  }

  // ── CursorMoveDto ───────────────────────────────────────────────────────────

  @Test
  void cursorMoveDtoSerializesXRatioAndYRatioAsCamelCase() throws Exception {
    CursorMoveDto dto = new CursorMoveDto("alice", 0.35, 0.72, false, "hsl(200,72%,50%)");
    String json = mapper.writeValueAsString(dto);

    assertTrue(json.contains("\"xRatio\""),  "Expected \"xRatio\" in JSON: "   + json);
    assertTrue(json.contains("\"yRatio\""),  "Expected \"yRatio\" in JSON: "   + json);
    assertFalse(json.contains("\"xratio\""), "Unexpected \"xratio\" in JSON: " + json);
    assertFalse(json.contains("\"yratio\""), "Unexpected \"yratio\" in JSON: " + json);
  }

  @Test
  void cursorMoveDtoDeserializesNonZeroValuesFromCamelCaseJson() throws Exception {
    // Simulates a message sent by the frontend: camelCase keys, non-zero positions
    String json = """
        {"username":"alice","xRatio":0.35,"yRatio":0.72,"painting":false,"color":"hsl(200,72%,50%)"}
        """;
    CursorMoveDto dto = mapper.readValue(json, CursorMoveDto.class);

    assertEquals(0.35, dto.getXRatio(), 1e-9, "xRatio must not default to 0.0");
    assertEquals(0.72, dto.getYRatio(), 1e-9, "yRatio must not default to 0.0");
    assertEquals("alice", dto.getUsername());
  }

  @Test
  void cursorMoveDtoRoundTripPreservesAllFields() throws Exception {
    CursorMoveDto original = new CursorMoveDto("bob", 0.1, 0.9, true, "hsl(120,72%,50%)");
    CursorMoveDto copy = mapper.readValue(mapper.writeValueAsString(original), CursorMoveDto.class);

    assertEquals(original.getUsername(), copy.getUsername());
    assertEquals(original.getXRatio(),   copy.getXRatio(),  1e-9);
    assertEquals(original.getYRatio(),   copy.getYRatio(),  1e-9);
    assertEquals(original.getPainting(), copy.getPainting());
    assertEquals(original.getColor(),    copy.getColor());
  }

  // ── BoardMessageDto ─────────────────────────────────────────────────────────

  @Test
  void boardMessageDtoSerializesCamelCaseFieldNames() throws Exception {
    BoardMessageDto dto = new BoardMessageDto(
        "id-1", "carol", 0.5, 0.6, "hello", Instant.parse("2026-01-01T00:00:00Z"));
    String json = mapper.writeValueAsString(dto);

    assertTrue(json.contains("\"xRatio\""),    "Expected \"xRatio\" in JSON: "    + json);
    assertTrue(json.contains("\"yRatio\""),    "Expected \"yRatio\" in JSON: "    + json);
    assertTrue(json.contains("\"postedAt\""),  "Expected \"postedAt\" in JSON: "  + json);
    assertFalse(json.contains("\"xratio\""),   "Unexpected \"xratio\" in JSON: "  + json);
    assertFalse(json.contains("\"yratio\""),   "Unexpected \"yratio\" in JSON: "  + json);
    assertFalse(json.contains("\"postedat\""), "Unexpected \"postedat\" in JSON: " + json);
  }

  @Test
  void boardMessageDtoDeserializesFromCamelCaseJson() throws Exception {
    String json = """
        {"id":"msg-1","username":"carol","xRatio":0.5,"yRatio":0.6,"content":"hello","postedAt":"2026-01-01T00:00:00Z"}
        """;
    BoardMessageDto dto = mapper.readValue(json, BoardMessageDto.class);

    assertEquals(0.5,     dto.getXRatio(), 1e-9);
    assertEquals(0.6,     dto.getYRatio(), 1e-9);
    assertEquals("carol", dto.getUsername());
    assertNotNull(dto.getPostedAt());
  }

  // ── LoginResponseDto ────────────────────────────────────────────────────────

  @Test
  void loginResponseDtoSerializesBoardColorAsCamelCase() throws Exception {
    LoginResponseDto dto = new LoginResponseDto(
        true, "Login successful", "alice", "alice@example.com", "jwt-token", "hsl(200,72%,50%)");
    String json = mapper.writeValueAsString(dto);

    assertTrue(json.contains("\"boardColor\""),  "Expected \"boardColor\" in JSON: "   + json);
    assertFalse(json.contains("\"boardcolor\""), "Unexpected \"boardcolor\" in JSON: " + json);
  }

  // ── UpdateProfileResponseDto ────────────────────────────────────────────────

  @Test
  void updateProfileResponseDtoSerializesBoardColorAsCamelCase() throws Exception {
    UpdateProfileResponseDto dto = new UpdateProfileResponseDto(
        true, "Updated", "alice", "alice@example.com", "hsl(200,72%,50%)");
    String json = mapper.writeValueAsString(dto);

    assertTrue(json.contains("\"boardColor\""),  "Expected \"boardColor\" in JSON: "   + json);
    assertFalse(json.contains("\"boardcolor\""), "Unexpected \"boardcolor\" in JSON: " + json);
  }
}
