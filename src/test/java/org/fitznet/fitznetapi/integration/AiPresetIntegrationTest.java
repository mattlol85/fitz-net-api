package org.fitznet.fitznetapi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Preset CRUD, validation and visibility by permission over MockMvc. */
class AiPresetIntegrationTest extends AiChatTestBase {

  private static final String FULL =
      "{\"name\":\"Code reviewer\",\"description\":\"Blunt, cites line numbers\","
          + "\"model\":\"qwen2.5-coder:14b\",\"nodeId\":\"n1\",\"systemPrompt\":\"You are...\","
          + "\"temperature\":0.2,\"numCtx\":8192,\"visibility\":\"private\"}";

  private String create(String token, String body) throws Exception {
    return json(call(post("/ai/presets"), token, body).andExpect(status().isOk())).path("id").asText();
  }

  // ---- auth --------------------------------------------------------------------------------

  @Test
  void shouldRequireATokenOnEveryPresetEndpoint() throws Exception {
    call(get("/ai/presets"), null, null).andExpect(status().isUnauthorized());
    call(post("/ai/presets"), null, FULL).andExpect(status().isUnauthorized());
    call(put("/ai/presets/x"), null, FULL).andExpect(status().isUnauthorized());
    call(delete("/ai/presets/x"), null, null).andExpect(status().isUnauthorized());
  }

  @Test
  void shouldForbidUsersWithoutAiUserPermission() throws Exception {
    call(get("/ai/presets"), plain, null).andExpect(status().isForbidden());
    call(post("/ai/presets"), plain, FULL).andExpect(status().isForbidden());
    call(put("/ai/presets/x"), plain, FULL).andExpect(status().isForbidden());
    call(delete("/ai/presets/x"), plain, null).andExpect(status().isForbidden());
  }

  // ---- CRUD --------------------------------------------------------------------------------

  @Test
  void shouldRoundTripCreateListUpdateAndDelete() throws Exception {
    call(post("/ai/presets"), alice, FULL)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").isString())
        .andExpect(jsonPath("$.name").value("Code reviewer"))
        .andExpect(jsonPath("$.description").value("Blunt, cites line numbers"))
        .andExpect(jsonPath("$.model").value("qwen2.5-coder:14b"))
        .andExpect(jsonPath("$.nodeId").value("n1"))
        .andExpect(jsonPath("$.systemPrompt").value("You are..."))
        .andExpect(jsonPath("$.temperature").value(0.2))
        .andExpect(jsonPath("$.numCtx").value(8192))
        .andExpect(jsonPath("$.visibility").value("private"))
        .andExpect(jsonPath("$.requiredPermission").isEmpty())
        .andExpect(jsonPath("$.ownerUsername").value("alice"))
        .andExpect(jsonPath("$.editable").value(true))
        .andExpect(jsonPath("$.createdAt").isString())
        .andExpect(jsonPath("$.updatedAt").isString());
    String id = presets.findAll().get(0).getId();

    call(get("/ai/presets"), alice, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(id));

    call(put("/ai/presets/" + id), alice, "{\"name\":\"Renamed\",\"temperature\":1.5}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"))
        .andExpect(jsonPath("$.temperature").value(1.5))
        .andExpect(jsonPath("$.model").isEmpty())
        .andExpect(jsonPath("$.nodeId").isEmpty())
        .andExpect(jsonPath("$.systemPrompt").isEmpty())
        .andExpect(jsonPath("$.numCtx").isEmpty())
        .andExpect(jsonPath("$.ownerUsername").value("alice"))
        .andExpect(jsonPath("$.id").value(id));

    call(delete("/ai/presets/" + id), alice, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(true));
    call(get("/ai/presets"), alice, null).andExpect(jsonPath("$.items.length()").value(0));
    call(delete("/ai/presets/" + id), alice, null).andExpect(status().isNotFound());
  }

  @Test
  void shouldDefaultToPrivateAndTrimTheName() throws Exception {
    call(post("/ai/presets"), alice, "{\"name\":\"  Padded  \"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Padded"))
        .andExpect(jsonPath("$.visibility").value("private"));
  }

  @Test
  void shouldListPresetsByName() throws Exception {
    create(alice, "{\"name\":\"zeta\"}");
    create(alice, "{\"name\":\"Alpha\"}");

    call(get("/ai/presets"), alice, null)
        .andExpect(jsonPath("$.items[0].name").value("Alpha"))
        .andExpect(jsonPath("$.items[1].name").value("zeta"));
  }

  // ---- validation --------------------------------------------------------------------------

  @Test
  void shouldRejectEveryInvalidField() throws Exception {
    String[] bad = {
      "{}",
      "{\"name\":\"\"}",
      "{\"name\":\"   \"}",
      "{\"name\":\"" + "n".repeat(61) + "\"}",
      "{\"name\":\"ok\",\"description\":\"" + "d".repeat(201) + "\"}",
      "{\"name\":\"ok\",\"systemPrompt\":\"" + "s".repeat(8001) + "\"}",
      "{\"name\":\"ok\",\"temperature\":-0.1}",
      "{\"name\":\"ok\",\"temperature\":2.1}",
      "{\"name\":\"ok\",\"numCtx\":511}",
      "{\"name\":\"ok\",\"numCtx\":131073}",
      "{\"name\":\"ok\",\"visibility\":\"public\"}",
      "{\"name\":\"ok\",\"nodeId\":\"n1\"}",
      "{\"name\":\"ok\",\"requiredPermission\":\"RADARR\"}",
      "{\"name\":\"ok\",\"visibility\":\"private\",\"requiredPermission\":\"RADARR\"}",
    };
    for (String body : bad) {
      call(post("/ai/presets"), alice, body).andExpect(status().isBadRequest());
    }
    assertEquals(0, presets.count());
  }

  @Test
  void shouldAcceptTheBoundaryValues() throws Exception {
    call(
            post("/ai/presets"),
            alice,
            "{\"name\":\"" + "n".repeat(60) + "\",\"description\":\"" + "d".repeat(200)
                + "\",\"systemPrompt\":\"" + "s".repeat(8000)
                + "\",\"temperature\":0,\"numCtx\":512}")
        .andExpect(status().isOk());
    call(post("/ai/presets"), alice, "{\"name\":\"max\",\"temperature\":2,\"numCtx\":131072}")
        .andExpect(status().isOk());
    call(post("/ai/presets"), alice, "{\"name\":\"pinned\",\"model\":\"m\",\"nodeId\":\"n1\"}")
        .andExpect(status().isOk());
  }

  @Test
  void shouldApplyTheSameRulesOnUpdate() throws Exception {
    String id = create(alice, "{\"name\":\"first\"}");

    call(put("/ai/presets/" + id), alice, "{\"name\":\"\"}").andExpect(status().isBadRequest());
    call(put("/ai/presets/" + id), alice, "{\"name\":\"x\",\"nodeId\":\"n1\"}")
        .andExpect(status().isBadRequest());
    call(put("/ai/presets/" + id), alice, "{\"name\":\"x\",\"numCtx\":100}")
        .andExpect(status().isBadRequest());

    assertEquals("first", presets.findById(id).orElseThrow().getName());
  }

  // ---- private presets ---------------------------------------------------------------------

  @Test
  void shouldHideAPrivatePresetFromEveryoneButItsOwnerAdminIncluded() throws Exception {
    String id = create(alice, FULL);

    for (String other : new String[] {bob, admin}) {
      call(get("/ai/presets"), other, null).andExpect(jsonPath("$.items.length()").value(0));
      call(put("/ai/presets/" + id), other, FULL).andExpect(status().isNotFound());
      call(delete("/ai/presets/" + id), other, null).andExpect(status().isNotFound());
    }
    assertTrue(presets.findById(id).isPresent());
  }

  // ---- shared presets ----------------------------------------------------------------------

  @Test
  void shouldShowASharedPresetWithoutRequiredPermissionToEveryoneWithAiAccess() throws Exception {
    String id = create(admin, "{\"name\":\"Everyone\",\"visibility\":\"shared\"}");

    for (String user : new String[] {alice, bob, radarr}) {
      call(get("/ai/presets"), user, null)
          .andExpect(jsonPath("$.items.length()").value(1))
          .andExpect(jsonPath("$.items[0].id").value(id))
          .andExpect(jsonPath("$.items[0].visibility").value("shared"))
          .andExpect(jsonPath("$.items[0].ownerUsername").value("admin"))
          .andExpect(jsonPath("$.items[0].editable").value(false));
    }
    call(get("/ai/presets"), admin, null)
        .andExpect(jsonPath("$.items[0].editable").value(true));
  }

  @Test
  void shouldShowASharedPresetOnlyToHoldersOfTheRequiredPermissionAndAdmin() throws Exception {
    String id =
        create(
            admin,
            "{\"name\":\"Radarr crowd\",\"visibility\":\"shared\",\"requiredPermission\":\"RADARR\"}");

    call(get("/ai/presets"), radarr, null)
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].requiredPermission").value("RADARR"));
    call(get("/ai/presets"), alice, null).andExpect(jsonPath("$.items.length()").value(0));
    call(get("/ai/presets"), admin, null).andExpect(jsonPath("$.items.length()").value(1));
    call(put("/ai/presets/" + id), alice, "{\"name\":\"x\"}").andExpect(status().isNotFound());
    call(delete("/ai/presets/" + id), alice, null).andExpect(status().isNotFound());
  }

  @Test
  void shouldAnswer403WhenANonAdminManagesASharedPreset() throws Exception {
    String id = create(admin, "{\"name\":\"Shared\",\"visibility\":\"shared\"}");
    String own = create(alice, "{\"name\":\"Mine\"}");

    call(post("/ai/presets"), alice, "{\"name\":\"x\",\"visibility\":\"shared\"}")
        .andExpect(status().isForbidden());
    call(put("/ai/presets/" + id), alice, "{\"name\":\"x\",\"visibility\":\"shared\"}")
        .andExpect(status().isForbidden());
    call(delete("/ai/presets/" + id), alice, null).andExpect(status().isForbidden());
    call(put("/ai/presets/" + own), alice, "{\"name\":\"Mine\",\"visibility\":\"shared\"}")
        .andExpect(status().isForbidden());

    assertEquals("Shared", presets.findById(id).orElseThrow().getName());
    assertEquals("private", presets.findById(own).orElseThrow().getVisibility());
  }

  @Test
  void shouldLetAdminCreateUpdateAndDeleteSharedPresets() throws Exception {
    String id =
        create(admin, "{\"name\":\"Shared\",\"visibility\":\"shared\",\"requiredPermission\":\"RADARR\"}");

    call(put("/ai/presets/" + id), admin, "{\"name\":\"Shared v2\",\"visibility\":\"shared\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Shared v2"))
        .andExpect(jsonPath("$.requiredPermission").isEmpty())
        .andExpect(jsonPath("$.editable").value(true));
    call(delete("/ai/presets/" + id), admin, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(true));
  }

  @Test
  void shouldLetAdminShareHisOwnPrivatePresetAndTakeItBackToPrivate() throws Exception {
    String id = create(admin, "{\"name\":\"Mine\"}");

    JsonNode shared =
        json(
            call(put("/ai/presets/" + id), admin, "{\"name\":\"Mine\",\"visibility\":\"shared\"}")
                .andExpect(status().isOk()));
    assertEquals("shared", shared.path("visibility").asText());
    call(get("/ai/presets"), alice, null).andExpect(jsonPath("$.items.length()").value(1));

    call(put("/ai/presets/" + id), admin, "{\"name\":\"Mine\",\"visibility\":\"private\"}")
        .andExpect(status().isOk());
    call(get("/ai/presets"), alice, null).andExpect(jsonPath("$.items.length()").value(0));
  }
}
