package org.fitznet.fitznetapi.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.model.RecipeImage;
import org.fitznet.fitznetapi.repository.RecipeBookRepository;
import org.fitznet.fitznetapi.repository.RecipeImageRepository;
import org.fitznet.fitznetapi.repository.RecipeRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.support.TestUsers;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Lori-Net recipe book API over MockMvc and embedded Mongo: permissions, per-user isolation,
 * CRUD, ingredient search and index, chapters, photos, validation and tracing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EmbeddedMongoTestConfiguration.class)
class RecipeIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private UserRepository userRepository;
  @Autowired private RecipeBookRepository books;
  @Autowired private RecipeRepository recipes;
  @Autowired private RecipeImageRepository images;

  private String alice;
  private String bob;
  private String admin;
  private String plain;

  @BeforeEach
  void setUp() {
    clean();
    alice = TestUsers.saveUser(userRepository, jwtUtil, "alice", "USER", "LORINET_ACCESS");
    bob = TestUsers.saveUser(userRepository, jwtUtil, "bob", "USER", "LORINET_ACCESS");
    admin = TestUsers.saveUser(userRepository, jwtUtil, "admin", "USER", "ADMIN");
    plain = TestUsers.saveUser(userRepository, jwtUtil, "plain", "USER", "AI_USER");
  }

  @AfterEach
  void tearDown() {
    clean();
  }

  private void clean() {
    userRepository.deleteAll();
    books.deleteAll();
    recipes.deleteAll();
    images.deleteAll();
  }

  // ---- helpers -----------------------------------------------------------------------------

  private ResultActions call(MockHttpServletRequestBuilder builder, String token, String body)
      throws Exception {
    MockHttpServletRequestBuilder request = builder.contentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      request = request.header("Authorization", "Bearer " + token);
    }
    return mockMvc.perform(body == null ? request : request.content(body));
  }

  private JsonNode json(ResultActions actions) throws Exception {
    return MAPPER.readTree(actions.andReturn().getResponse().getContentAsString());
  }

  private JsonNode ok(MockHttpServletRequestBuilder builder, String token, String body)
      throws Exception {
    return json(call(builder, token, body).andExpect(status().isOk()));
  }

  private static String recipeJson(
      String title, String chapterId, String imageId, List<String> ingredientNames) {
    StringBuilder rows = new StringBuilder();
    for (String name : ingredientNames) {
      if (!rows.isEmpty()) {
        rows.append(',');
      }
      rows.append("{\"quantity\":\"1 1/2\",\"unit\":\"cup\",\"name\":\"")
          .append(name)
          .append("\",\"note\":\"sifted\"}");
    }
    return "{\"title\":\""
        + title
        + "\","
        + (chapterId == null ? "" : "\"chapterId\":\"" + chapterId + "\",")
        + (imageId == null ? "" : "\"imageId\":\"" + imageId + "\",")
        + "\"servings\":4,\"prepMinutes\":10,\"cookMinutes\":20,"
        + "\"ingredients\":["
        + rows
        + "],\"instructions\":[\"Mix everything.\",\"Cook it.\"]}";
  }

  private String createRecipe(String token, String title, String chapterId, String... ingredients)
      throws Exception {
    return ok(post("/recipes"), token, recipeJson(title, chapterId, null, List.of(ingredients)))
        .path("id")
        .asText();
  }

  private static byte[] realPng(int width, int height) throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  private String uploadImage(String token, byte[] png) throws Exception {
    String body =
        "{\"mimeType\":\"image/png\",\"dataBase64\":\""
            + Base64.getEncoder().encodeToString(png)
            + "\"}";
    return ok(post("/recipe-images"), token, body).path("id").asText();
  }

  private static List<String> ids(JsonNode list) {
    List<String> ids = new ArrayList<>();
    list.path("items").forEach(item -> ids.add(item.path("id").asText()));
    return ids;
  }

  private String chapterId(String token, String name) throws Exception {
    for (JsonNode chapter : ok(get("/recipe-book"), token, null).path("chapters")) {
      if (chapter.path("name").asText().equals(name)) {
        return chapter.path("id").asText();
      }
    }
    throw new AssertionError("no chapter " + name);
  }

  // ---- auth --------------------------------------------------------------------------------

  @Test
  void shouldRequireATokenOnEveryRecipeEndpoint() throws Exception {
    call(get("/recipe-book"), null, null).andExpect(status().isUnauthorized());
    call(get("/recipes"), null, null).andExpect(status().isUnauthorized());
    call(get("/recipe-images/x"), null, null).andExpect(status().isUnauthorized());
  }

  @Test
  void shouldForbidUsersWithoutLorinetAccess() throws Exception {
    String recipe = recipeJson("Toast", null, null, List.of("bread"));
    call(get("/recipe-book"), plain, null).andExpect(status().isForbidden());
    call(put("/recipe-book"), plain, "{\"title\":\"x\",\"coverColor\":\"rose\",\"chapters\":[]}")
        .andExpect(status().isForbidden());
    call(get("/recipes"), plain, null).andExpect(status().isForbidden());
    call(get("/recipes/ingredients"), plain, null).andExpect(status().isForbidden());
    call(get("/recipes/x"), plain, null).andExpect(status().isForbidden());
    call(post("/recipes"), plain, recipe).andExpect(status().isForbidden());
    call(put("/recipes/x"), plain, recipe).andExpect(status().isForbidden());
    call(delete("/recipes/x"), plain, null).andExpect(status().isForbidden());
    call(post("/recipe-images"), plain, "{\"mimeType\":\"image/png\",\"dataBase64\":\"AA==\"}")
        .andExpect(status().isForbidden());
    call(get("/recipe-images/x"), plain, null).andExpect(status().isForbidden());
    assertEquals(0, books.count());
  }

  @Test
  void shouldLetAdminUseOnlyTheirOwnBook() throws Exception {
    String aliceRecipe = createRecipe(alice, "Alice's soup", null, "leek");
    JsonNode book = ok(get("/recipe-book"), admin, null);
    assertEquals("admin's Recipe Book", book.path("title").asText());
    assertEquals(0, book.path("recipeCount").asLong());
    call(get("/recipes/" + aliceRecipe), admin, null).andExpect(status().isNotFound());
    assertEquals(0, ok(get("/recipes"), admin, null).path("total").asLong());
  }

  // ---- book --------------------------------------------------------------------------------

  @Test
  void shouldCreateTheBookLazilyWithDefaultChaptersOnce() throws Exception {
    assertEquals(0, books.count());
    JsonNode first = ok(get("/recipe-book"), alice, null);
    JsonNode second = ok(get("/recipe-book"), alice, null);

    assertEquals(1, books.count());
    assertEquals(first.path("id").asText(), second.path("id").asText());
    assertEquals("alice's Recipe Book", first.path("title").asText());
    assertEquals("rose", first.path("coverColor").asText());
    assertTrue(first.path("createdAt").isString());
    assertEquals(0, first.path("recipeCount").asLong());
    List<String> names = new ArrayList<>();
    first.path("chapters").forEach(c -> names.add(c.path("name").asText()));
    assertEquals(
        List.of("Breakfast", "Soups & Salads", "Mains", "Sides", "Baking", "Desserts", "Drinks"),
        names);
    JsonNode chapter = first.path("chapters").get(0);
    assertFalse(chapter.path("id").asText().isBlank());
    assertFalse(chapter.path("tabColor").asText().isBlank());
    assertEquals(0, chapter.path("recipeCount").asLong());
  }

  @Test
  void shouldReplaceChaptersAndMoveRecipesOfRemovedChaptersToUnsorted() throws Exception {
    String breakfast = chapterId(alice, "Breakfast");
    String mains = chapterId(alice, "Mains");
    String pancakes = createRecipe(alice, "Pancakes", breakfast, "egg");
    String stew = createRecipe(alice, "Stew", mains, "beef");
    String bobsBreakfast = chapterId(bob, "Breakfast");
    String bobs = createRecipe(bob, "Bob's eggs", bobsBreakfast, "egg");

    JsonNode book = ok(get("/recipe-book"), alice, null);
    assertEquals(2, book.path("recipeCount").asLong());
    assertEquals(1, book.path("chapters").get(0).path("recipeCount").asLong());

    String update =
        "{\"title\":\"Lori's Kitchen\",\"coverColor\":\"forest\",\"chapters\":["
            + "{\"id\":\""
            + mains
            + "\",\"name\":\"Dinners\",\"tabColor\":\"#aa3355\"},"
            + "{\"name\":\"Holiday\",\"tabColor\":\"gold\"}]}";
    JsonNode updated = ok(put("/recipe-book"), alice, update);

    assertEquals("Lori's Kitchen", updated.path("title").asText());
    assertEquals("forest", updated.path("coverColor").asText());
    assertEquals(2, updated.path("chapters").size());
    assertEquals(mains, updated.path("chapters").get(0).path("id").asText());
    assertEquals("Dinners", updated.path("chapters").get(0).path("name").asText());
    assertEquals(1, updated.path("chapters").get(0).path("recipeCount").asLong());
    String holiday = updated.path("chapters").get(1).path("id").asText();
    assertFalse(holiday.isBlank());
    assertNotEquals(mains, holiday);
    assertEquals(2, updated.path("recipeCount").asLong());

    assertTrue(ok(get("/recipes/" + pancakes), alice, null).path("chapterId").isNull());
    assertEquals(mains, ok(get("/recipes/" + stew), alice, null).path("chapterId").asText());
    // Another user's recipes in a chapter with the same id are untouched.
    assertEquals(bobsBreakfast, ok(get("/recipes/" + bobs), bob, null).path("chapterId").asText());
    // The removed chapter can no longer be used.
    call(post("/recipes"), alice, recipeJson("Waffles", breakfast, null, List.of("egg")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldRejectInvalidBookUpdates() throws Exception {
    call(put("/recipe-book"), alice, "{\"title\":\"\",\"coverColor\":\"rose\",\"chapters\":[]}")
        .andExpect(status().isBadRequest());
    call(put("/recipe-book"), alice, "{\"title\":\"Book\",\"coverColor\":\"pink\",\"chapters\":[]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.coverColor").exists());
    call(
            put("/recipe-book"),
            alice,
            "{\"title\":\"Book\",\"coverColor\":\"rose\",\"chapters\":[{\"name\":\"\",\"tabColor\":\"sage\"}]}")
        .andExpect(status().isBadRequest());
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 21; i++) {
      many.append(i == 0 ? "" : ",").append("{\"name\":\"C").append(i).append("\",\"tabColor\":\"sage\"}");
    }
    call(
            put("/recipe-book"),
            alice,
            "{\"title\":\"Book\",\"coverColor\":\"rose\",\"chapters\":[" + many + "]}")
        .andExpect(status().isBadRequest());
    String id = chapterId(alice, "Mains");
    String duplicate =
        "{\"title\":\"Book\",\"coverColor\":\"rose\",\"chapters\":["
            + "{\"id\":\"" + id + "\",\"name\":\"A\",\"tabColor\":\"sage\"},"
            + "{\"id\":\"" + id + "\",\"name\":\"B\",\"tabColor\":\"sage\"}]}";
    call(put("/recipe-book"), alice, duplicate).andExpect(status().isBadRequest());
  }

  // ---- recipes -----------------------------------------------------------------------------

  @Test
  void shouldRoundTripCreateGetUpdateAndDeleteKeepingTheDateAdded() throws Exception {
    String breakfast = chapterId(alice, "Breakfast");
    JsonNode created =
        ok(post("/recipes"), alice, recipeJson("Pancakes", breakfast, null, List.of("Eggs", "Flour")));
    String id = created.path("id").asText();
    assertEquals("Pancakes", created.path("title").asText());
    assertEquals(breakfast, created.path("chapterId").asText());
    assertEquals(4, created.path("servings").asInt());
    assertEquals(10, created.path("prepMinutes").asInt());
    assertEquals(20, created.path("cookMinutes").asInt());
    assertEquals("1 1/2", created.path("ingredients").get(0).path("quantity").asText());
    assertEquals("cup", created.path("ingredients").get(0).path("unit").asText());
    assertEquals("Eggs", created.path("ingredients").get(0).path("name").asText());
    assertEquals("sifted", created.path("ingredients").get(0).path("note").asText());
    assertEquals("egg", created.path("ingredientNames").get(0).asText());
    assertEquals("flour", created.path("ingredientNames").get(1).asText());
    assertEquals(2, created.path("instructions").size());
    assertTrue(created.path("imageId").isNull());
    String createdAt = created.path("createdAt").asText();
    assertFalse(createdAt.isBlank());

    JsonNode fetched = ok(get("/recipes/" + id), alice, null);
    assertEquals(created, fetched);

    Thread.sleep(5);
    JsonNode updated =
        ok(put("/recipes/" + id), alice, recipeJson("Fluffy pancakes", null, null, List.of("milk")));
    assertEquals(id, updated.path("id").asText());
    assertEquals("Fluffy pancakes", updated.path("title").asText());
    assertTrue(updated.path("chapterId").isNull());
    assertEquals("milk", updated.path("ingredientNames").get(0).asText());
    assertEquals(createdAt, updated.path("createdAt").asText());
    assertNotEquals(createdAt, updated.path("updatedAt").asText());

    JsonNode deleted = ok(delete("/recipes/" + id), alice, null);
    assertEquals(id, deleted.path("id").asText());
    assertTrue(deleted.path("deleted").asBoolean());
    call(get("/recipes/" + id), alice, null).andExpect(status().isNotFound());
    assertEquals(0, recipes.count());
  }

  @Test
  void shouldIsolateEachUsersRecipesAndImages() throws Exception {
    String imageId = uploadImage(alice, realPng(8, 8));
    String id = createRecipe(alice, "Secret sauce", null, "garlic");
    String body = recipeJson("Stolen", null, null, List.of("garlic"));

    call(get("/recipes/" + id), bob, null).andExpect(status().isNotFound());
    call(put("/recipes/" + id), bob, body).andExpect(status().isNotFound());
    call(delete("/recipes/" + id), bob, null).andExpect(status().isNotFound());
    call(get("/recipe-images/" + imageId), bob, null).andExpect(status().isNotFound());
    call(post("/recipes"), bob, recipeJson("Mine", null, imageId, List.of("salt")))
        .andExpect(status().isBadRequest());
    assertEquals(0, ok(get("/recipes"), bob, null).path("total").asLong());
    assertEquals(0, ok(get("/recipes/ingredients"), bob, null).path("entries").size());

    assertEquals("Secret sauce", ok(get("/recipes/" + id), alice, null).path("title").asText());
    call(get("/recipes/not-an-object-id"), alice, null).andExpect(status().isNotFound());
  }

  @Test
  void shouldSearchByEveryIngredientWithPluralAndCaseFolding() throws Exception {
    String breakfast = chapterId(alice, "Breakfast");
    String pancakes = createRecipe(alice, "Pancakes", breakfast, "Eggs", "Plain flour", "Milk");
    String omelette = createRecipe(alice, "Omelette", breakfast, "egg", "Cheese");
    String shortbread = createRecipe(alice, "Shortbread", null, "flour", "butter");
    String soup = createRecipe(alice, "Tomato soup (easy)", null, "Tomatoes", "Boneless chicken thighs");
    createRecipe(bob, "Bob's omelette", null, "eggs");

    assertEquals(
        List.of(pancakes),
        ids(ok(get("/recipes").param("ingredient", "egg").param("ingredient", "FLOUR"), alice, null)));
    assertEquals(
        List.of(omelette, pancakes), ids(ok(get("/recipes").param("ingredient", "EGGS"), alice, null)));
    assertEquals(
        List.of(shortbread, pancakes), ids(ok(get("/recipes").param("ingredient", "flours"), alice, null)));
    assertEquals(List.of(soup), ids(ok(get("/recipes").param("ingredient", "tomato"), alice, null)));
    assertEquals(
        List.of(soup), ids(ok(get("/recipes").param("ingredient", "chicken thigh"), alice, null)));
    assertEquals(List.of(), ids(ok(get("/recipes").param("ingredient", "egg").param("ingredient", "beef"), alice, null)));

    assertEquals(List.of(pancakes), ids(ok(get("/recipes").param("q", "PAN"), alice, null)));
    assertEquals(List.of(soup), ids(ok(get("/recipes").param("q", "(easy)"), alice, null)));
    assertEquals(List.of(), ids(ok(get("/recipes").param("q", ".*"), alice, null)));
    assertEquals(
        List.of(omelette, pancakes), ids(ok(get("/recipes").param("chapterId", breakfast), alice, null)));

    JsonNode all = ok(get("/recipes"), alice, null);
    assertEquals(4, all.path("total").asLong());
    assertEquals(List.of(soup, shortbread, omelette, pancakes), ids(all));
    JsonNode summary = all.path("items").get(0);
    assertEquals("Tomato soup (easy)", summary.path("title").asText());
    assertEquals(4, summary.path("servings").asInt());
    assertEquals("tomato", summary.path("ingredientNames").get(0).asText());
    assertTrue(summary.path("createdAt").isString());
    assertTrue(summary.path("ingredients").isMissingNode());
    assertTrue(summary.path("instructions").isMissingNode());
    assertTrue(summary.path("ingredientTerms").isMissingNode());
  }

  @Test
  void shouldBuildTheIngredientIndexAToZ() throws Exception {
    String pancakes = createRecipe(alice, "Pancakes", null, "Eggs", "flour", "egg");
    String omelette = createRecipe(alice, "Omelette", null, "egg", "chives");
    createRecipe(bob, "Bob's bread", null, "yeast");

    JsonNode entries = ok(get("/recipes/ingredients"), alice, null).path("entries");
    List<String> names = new ArrayList<>();
    entries.forEach(e -> names.add(e.path("name").asText()));
    assertEquals(List.of("chive", "egg", "flour"), names);
    JsonNode egg = entries.get(1);
    assertEquals(2, egg.path("recipes").size());
    assertEquals(omelette, egg.path("recipes").get(0).path("id").asText());
    assertEquals("Omelette", egg.path("recipes").get(0).path("title").asText());
    assertEquals(pancakes, egg.path("recipes").get(1).path("id").asText());
  }

  @Test
  void shouldRejectInvalidRecipes() throws Exception {
    call(post("/recipes"), alice, recipeJson("", null, null, List.of("egg")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.title").exists());
    call(post("/recipes"), alice, recipeJson("Nothing", null, null, List.of()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.ingredients").exists());
    call(post("/recipes"), alice, recipeJson("x".repeat(121), null, null, List.of("egg")))
        .andExpect(status().isBadRequest());
    call(post("/recipes"), alice, recipeJson("Blank row", null, null, List.of(" ")))
        .andExpect(status().isBadRequest());
    call(post("/recipes"), alice, recipeJson("Symbols", null, null, List.of("!!!")))
        .andExpect(status().isBadRequest());
    call(
            post("/recipes"),
            alice,
            recipeJson("Servings", null, null, List.of("egg")).replace("\"servings\":4", "\"servings\":0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.servings").exists());
    call(
            post("/recipes"),
            alice,
            recipeJson("Long", null, null, List.of("egg")).replace("\"prepMinutes\":10", "\"prepMinutes\":1441"))
        .andExpect(status().isBadRequest());
    call(
            post("/recipes"),
            alice,
            recipeJson("No steps", null, null, List.of("egg"))
                .replace("[\"Mix everything.\",\"Cook it.\"]", "[]"))
        .andExpect(status().isBadRequest());
    call(
            post("/recipes"),
            alice,
            recipeJson("Blank step", null, null, List.of("egg"))
                .replace("\"Cook it.\"", "\" \""))
        .andExpect(status().isBadRequest());
    call(post("/recipes"), alice, recipeJson("Bad chapter", "nope", null, List.of("egg")))
        .andExpect(status().isBadRequest());
    call(post("/recipes"), alice, recipeJson("Bad image", null, "nope", List.of("egg")))
        .andExpect(status().isBadRequest());
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      many.add("item" + i);
    }
    call(post("/recipes"), alice, recipeJson("Too many", null, null, many))
        .andExpect(status().isBadRequest());
    MockHttpServletRequestBuilder search = get("/recipes");
    for (int i = 0; i < 21; i++) {
      search = search.param("ingredient", "egg" + i);
    }
    call(search, alice, null).andExpect(status().isBadRequest());
    assertEquals(0, recipes.count());
  }

  // ---- images ------------------------------------------------------------------------------

  @Test
  void shouldUploadAndServeAnImageWithItsSizeAndCacheHeader() throws Exception {
    byte[] png = realPng(40, 30);
    JsonNode uploaded =
        ok(
            post("/recipe-images"),
            alice,
            "{\"mimeType\":\"image/png\",\"dataBase64\":\""
                + Base64.getEncoder().encodeToString(png)
                + "\"}");
    String id = uploaded.path("id").asText();
    assertEquals("image/png", uploaded.path("mimeType").asText());
    assertEquals(png.length, uploaded.path("sizeBytes").asLong());
    assertEquals(40, uploaded.path("width").asInt());
    assertEquals(30, uploaded.path("height").asInt());
    assertTrue(uploaded.path("createdAt").isString());
    assertTrue(uploaded.path("data").isMissingNode());

    RecipeImage stored = images.findById(id).orElseThrow();
    assertNull(stored.getRecipeId());
    assertNotNull(stored.getExpiresAt());

    byte[] served =
        call(get("/recipe-images/" + id), alice, null)
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertArrayEquals(png, served);
  }

  @Test
  void shouldRejectUnsupportedMismatchedAndOversizedImages() throws Exception {
    byte[] png = realPng(4, 4);
    String b64 = Base64.getEncoder().encodeToString(png);
    call(post("/recipe-images"), alice, "{\"mimeType\":\"image/gif\",\"dataBase64\":\"" + b64 + "\"}")
        .andExpect(status().isBadRequest());
    call(post("/recipe-images"), alice, "{\"mimeType\":\"image/jpeg\",\"dataBase64\":\"" + b64 + "\"}")
        .andExpect(status().isBadRequest());
    call(post("/recipe-images"), alice, "{\"mimeType\":\"image/png\",\"dataBase64\":\"%%%\"}")
        .andExpect(status().isBadRequest());
    call(post("/recipe-images"), alice, "{\"mimeType\":\"image/png\",\"dataBase64\":\"\"}")
        .andExpect(status().isBadRequest());
    byte[] big = new byte[2 * 1024 * 1024 + 1];
    System.arraycopy(png, 0, big, 0, png.length);
    call(
            post("/recipe-images"),
            alice,
            "{\"mimeType\":\"image/png\",\"dataBase64\":\"" + Base64.getEncoder().encodeToString(big) + "\"}")
        .andExpect(status().isBadRequest());
    assertEquals(0, images.count());
  }

  @Test
  void shouldClaimReplaceAndDeleteARecipesPhoto() throws Exception {
    String first = uploadImage(alice, realPng(10, 10));
    JsonNode created = ok(post("/recipes"), alice, recipeJson("Pie", null, first, List.of("apple")));
    String id = created.path("id").asText();
    assertEquals(first, created.path("imageId").asText());
    RecipeImage claimed = images.findById(first).orElseThrow();
    assertEquals(id, claimed.getRecipeId());
    assertNull(claimed.getExpiresAt());

    // An image can belong to one recipe only.
    call(post("/recipes"), alice, recipeJson("Other pie", null, first, List.of("pear")))
        .andExpect(status().isBadRequest());

    // Saving again with the same photo keeps it.
    ok(put("/recipes/" + id), alice, recipeJson("Apple pie", null, first, List.of("apple")));
    assertTrue(images.existsById(first));

    String second = uploadImage(alice, realPng(12, 12));
    JsonNode replaced = ok(put("/recipes/" + id), alice, recipeJson("Apple pie", null, second, List.of("apple")));
    assertEquals(second, replaced.path("imageId").asText());
    assertFalse(images.existsById(first));
    assertEquals(id, images.findById(second).orElseThrow().getRecipeId());

    JsonNode removed = ok(put("/recipes/" + id), alice, recipeJson("Apple pie", null, null, List.of("apple")));
    assertTrue(removed.path("imageId").isNull());
    assertFalse(images.existsById(second));

    String third = uploadImage(alice, realPng(14, 14));
    ok(put("/recipes/" + id), alice, recipeJson("Apple pie", null, third, List.of("apple")));
    ok(delete("/recipes/" + id), alice, null);
    assertFalse(images.existsById(third));
    assertEquals(0, images.count());
  }

  // ---- tracing -----------------------------------------------------------------------------

  @Test
  void shouldEchoTheCorrelationIdOnRecipeEndpoints() throws Exception {
    mockMvc
        .perform(
            get("/recipe-book")
                .header("Authorization", "Bearer " + alice)
                .header("X-Correlation-ID", "web-recipe01"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "web-recipe01"));
    mockMvc
        .perform(
            get("/recipes")
                .header("Authorization", "Bearer " + alice)
                .header("X-Correlation-ID", "web-recipe02"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "web-recipe02"));
    mockMvc
        .perform(get("/recipes/missing").header("Authorization", "Bearer " + alice))
        .andExpect(status().isNotFound())
        .andExpect(header().exists("X-Correlation-ID"));
  }
}
