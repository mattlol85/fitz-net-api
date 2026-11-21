package org.fitznet.fitznetapi.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.fitznet.fitznetapi.config.EmbeddedMongoTestConfiguration;
import org.fitznet.fitznetapi.grpc.proto.CreateRecipeRequest;
import org.fitznet.fitznetapi.grpc.proto.DeleteRecipeRequest;
import org.fitznet.fitznetapi.grpc.proto.DeleteRecipeResponse;
import org.fitznet.fitznetapi.grpc.proto.GetIngredientIndexRequest;
import org.fitznet.fitznetapi.grpc.proto.GetRecipeBookRequest;
import org.fitznet.fitznetapi.grpc.proto.GetRecipeImageRequest;
import org.fitznet.fitznetapi.grpc.proto.GetRecipeRequest;
import org.fitznet.fitznetapi.grpc.proto.IngredientIndex;
import org.fitznet.fitznetapi.grpc.proto.ListRecipesRequest;
import org.fitznet.fitznetapi.grpc.proto.ListRecipesResponse;
import org.fitznet.fitznetapi.grpc.proto.Recipe;
import org.fitznet.fitznetapi.grpc.proto.RecipeBook;
import org.fitznet.fitznetapi.grpc.proto.RecipeBookServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.RecipeChapterInput;
import org.fitznet.fitznetapi.grpc.proto.RecipeImage;
import org.fitznet.fitznetapi.grpc.proto.RecipeImageContent;
import org.fitznet.fitznetapi.grpc.proto.RecipeImageServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.RecipeIngredient;
import org.fitznet.fitznetapi.grpc.proto.RecipeInput;
import org.fitznet.fitznetapi.grpc.proto.RecipeServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.UpdateRecipeBookRequest;
import org.fitznet.fitznetapi.grpc.proto.UpdateRecipeRequest;
import org.fitznet.fitznetapi.grpc.proto.UploadRecipeImageRequest;
import org.fitznet.fitznetapi.repository.RecipeBookRepository;
import org.fitznet.fitznetapi.repository.RecipeImageRepository;
import org.fitznet.fitznetapi.repository.RecipeRepository;
import org.fitznet.fitznetapi.repository.UserRepository;
import org.fitznet.fitznetapi.support.TestUsers;
import org.fitznet.fitznetapi.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The recipe book, recipe and recipe image RPCs over a real socket. */
@SpringBootTest
@Import(EmbeddedMongoTestConfiguration.class)
class RecipeGrpcIntegrationTest {

  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> HTTP_STATUS =
      Metadata.Key.of("x-http-status", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> CORRELATION_ID =
      Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

  @LocalGrpcServerPort private int port;
  @Autowired private UserRepository userRepository;
  @Autowired private RecipeBookRepository books;
  @Autowired private RecipeRepository recipes;
  @Autowired private RecipeImageRepository images;
  @Autowired private JwtUtil jwtUtil;

  private ManagedChannel channel;
  private String alice;
  private String bob;
  private String plain;

  @BeforeEach
  void setUp() {
    channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    clean();
    alice = TestUsers.saveUser(userRepository, jwtUtil, "alice", "USER", "LORINET_ACCESS");
    bob = TestUsers.saveUser(userRepository, jwtUtil, "bob", "USER", "LORINET_ACCESS");
    plain = TestUsers.saveUser(userRepository, jwtUtil, "plain", "USER");
  }

  @AfterEach
  void tearDown() {
    channel.shutdownNow();
    clean();
  }

  private void clean() {
    userRepository.deleteAll();
    books.deleteAll();
    recipes.deleteAll();
    images.deleteAll();
  }

  private <S extends AbstractStub<S>> S withToken(S stub, String token) {
    Metadata headers = new Metadata();
    headers.put(AUTHORIZATION, "Bearer " + token);
    return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  private RecipeBookServiceGrpc.RecipeBookServiceBlockingStub bookAs(String token) {
    return withToken(RecipeBookServiceGrpc.newBlockingStub(channel), token);
  }

  private RecipeServiceGrpc.RecipeServiceBlockingStub recipesAs(String token) {
    return withToken(RecipeServiceGrpc.newBlockingStub(channel), token);
  }

  private RecipeImageServiceGrpc.RecipeImageServiceBlockingStub imagesAs(String token) {
    return withToken(RecipeImageServiceGrpc.newBlockingStub(channel), token);
  }

  private static void assertStatus(Status.Code code, String httpStatus, Executable call) {
    StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, call);
    assertEquals(code, ex.getStatus().getCode(), ex.getStatus().toString());
    assertEquals(httpStatus, ex.getTrailers().get(HTTP_STATUS));
  }

  private static RecipeInput input(String title, String... ingredients) {
    RecipeInput.Builder b =
        RecipeInput.newBuilder().setTitle(title).setServings(2).addInstructions("Stir well.");
    for (String name : ingredients) {
      b.addIngredients(RecipeIngredient.newBuilder().setQuantity("2").setName(name));
    }
    return b.build();
  }

  private static byte[] realPng(int width, int height) throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  // ---- RecipeBookService -------------------------------------------------------------------

  @Test
  void shouldGetAndUpdateTheRecipeBook() {
    RecipeBook book = bookAs(alice).getRecipeBook(GetRecipeBookRequest.getDefaultInstance());
    assertEquals("alice's Recipe Book", book.getTitle());
    assertEquals("rose", book.getCoverColor());
    assertEquals(7, book.getChaptersCount());
    assertTrue(book.hasCreatedAt());

    String keep = book.getChapters(2).getId();
    RecipeBook updated =
        bookAs(alice)
            .updateRecipeBook(
                UpdateRecipeBookRequest.newBuilder()
                    .setTitle("Kitchen")
                    .setCoverColor("navy")
                    .addChapters(
                        RecipeChapterInput.newBuilder().setId(keep).setName("Dinners").setTabColor("rose"))
                    .addChapters(RecipeChapterInput.newBuilder().setName("New").setTabColor("sage"))
                    .build());
    assertEquals("Kitchen", updated.getTitle());
    assertEquals("navy", updated.getCoverColor());
    assertEquals(2, updated.getChaptersCount());
    assertEquals(keep, updated.getChapters(0).getId());
    assertFalse(updated.getChapters(1).getId().isBlank());
  }

  @Test
  void shouldRejectRecipeBookCallsWithoutAccessOrWithInvalidInput() {
    assertStatus(
        Status.Code.UNAUTHENTICATED,
        "401",
        () ->
            RecipeBookServiceGrpc.newBlockingStub(channel)
                .getRecipeBook(GetRecipeBookRequest.getDefaultInstance()));
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        "403",
        () -> bookAs(plain).getRecipeBook(GetRecipeBookRequest.getDefaultInstance()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () ->
            bookAs(alice)
                .updateRecipeBook(
                    UpdateRecipeBookRequest.newBuilder().setTitle("Book").setCoverColor("pink").build()));
  }

  // ---- RecipeService -----------------------------------------------------------------------

  @Test
  void shouldRoundTripRecipesSearchAndIndex() {
    Recipe created = recipesAs(alice).createRecipe(CreateRecipeRequest.newBuilder().setRecipe(input("Pancakes", "Eggs", "flour")).build());
    assertFalse(created.getId().isBlank());
    assertEquals(List.of("egg", "flour"), created.getIngredientNamesList());
    assertEquals("2", created.getIngredients(0).getQuantity());
    assertFalse(created.getIngredients(0).hasUnit());
    assertFalse(created.hasChapterId());
    assertEquals(2, created.getServings());
    assertFalse(created.hasPrepMinutes());
    recipesAs(alice).createRecipe(CreateRecipeRequest.newBuilder().setRecipe(input("Omelette", "egg")).build());

    ListRecipesResponse both =
        recipesAs(alice).listRecipes(ListRecipesRequest.newBuilder().addIngredients("EGGS").build());
    assertEquals(2, both.getTotal());
    ListRecipesResponse one =
        recipesAs(alice)
            .listRecipes(ListRecipesRequest.newBuilder().addIngredients("egg").addIngredients("flours").build());
    assertEquals(1, one.getItemsCount());
    assertEquals(created.getId(), one.getItems(0).getId());
    assertEquals(1, recipesAs(alice).listRecipes(ListRecipesRequest.newBuilder().setQ("omel").build()).getTotal());

    IngredientIndex index = recipesAs(alice).getIngredientIndex(GetIngredientIndexRequest.getDefaultInstance());
    assertEquals("egg", index.getEntries(0).getName());
    assertEquals(2, index.getEntries(0).getRecipesCount());

    Recipe fetched =
        recipesAs(alice).getRecipe(GetRecipeRequest.newBuilder().setRecipeId(created.getId()).build());
    assertEquals(created, fetched);

    Recipe updated =
        recipesAs(alice)
            .updateRecipe(
                UpdateRecipeRequest.newBuilder()
                    .setRecipeId(created.getId())
                    .setRecipe(input("Crepes", "milk"))
                    .build());
    assertEquals("Crepes", updated.getTitle());
    assertEquals(created.getCreatedAt(), updated.getCreatedAt());

    assertStatus(
        Status.Code.NOT_FOUND,
        "404",
        () -> recipesAs(bob).getRecipe(GetRecipeRequest.newBuilder().setRecipeId(created.getId()).build()));

    DeleteRecipeResponse deleted =
        recipesAs(alice).deleteRecipe(DeleteRecipeRequest.newBuilder().setRecipeId(created.getId()).build());
    assertTrue(deleted.getDeleted());
    assertEquals(created.getId(), deleted.getId());
    assertStatus(
        Status.Code.NOT_FOUND,
        "404",
        () -> recipesAs(alice).getRecipe(GetRecipeRequest.newBuilder().setRecipeId(created.getId()).build()));
  }

  @Test
  void shouldRejectRecipeCallsWithoutAccessOrWithInvalidInput() {
    assertStatus(
        Status.Code.UNAUTHENTICATED,
        "401",
        () -> RecipeServiceGrpc.newBlockingStub(channel).listRecipes(ListRecipesRequest.getDefaultInstance()));
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        "403",
        () -> recipesAs(plain).listRecipes(ListRecipesRequest.getDefaultInstance()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> recipesAs(alice).createRecipe(CreateRecipeRequest.getDefaultInstance()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> recipesAs(alice).createRecipe(CreateRecipeRequest.newBuilder().setRecipe(input("No ingredients")).build()));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> recipesAs(alice).getRecipe(GetRecipeRequest.getDefaultInstance()));
    assertEquals(0, recipes.count());
  }

  // ---- RecipeImageService ------------------------------------------------------------------

  @Test
  void shouldRoundTripImageBytesAndClaimThem() throws IOException {
    byte[] png = realPng(20, 10);
    RecipeImage uploaded =
        imagesAs(alice)
            .uploadRecipeImage(
                UploadRecipeImageRequest.newBuilder()
                    .setMimeType("image/png")
                    .setData(ByteString.copyFrom(png))
                    .build());
    assertEquals(png.length, uploaded.getSizeBytes());
    assertEquals(20, uploaded.getWidth());
    assertEquals(10, uploaded.getHeight());

    RecipeImageContent content =
        imagesAs(alice).getRecipeImage(GetRecipeImageRequest.newBuilder().setImageId(uploaded.getId()).build());
    assertEquals("image/png", content.getMimeType());
    assertArrayEquals(png, content.getData().toByteArray());

    assertStatus(
        Status.Code.NOT_FOUND,
        "404",
        () -> imagesAs(bob).getRecipeImage(GetRecipeImageRequest.newBuilder().setImageId(uploaded.getId()).build()));

    Recipe recipe =
        recipesAs(alice)
            .createRecipe(
                CreateRecipeRequest.newBuilder()
                    .setRecipe(input("Photo pie", "apple").toBuilder().setImageId(uploaded.getId()))
                    .build());
    assertEquals(uploaded.getId(), recipe.getImageId());
    assertEquals(recipe.getId(), images.findById(uploaded.getId()).orElseThrow().getRecipeId());
  }

  @Test
  void shouldRejectImageCallsWithoutAccessOrWithInvalidInput() {
    UploadRecipeImageRequest gif =
        UploadRecipeImageRequest.newBuilder()
            .setMimeType("image/gif")
            .setData(ByteString.copyFromUtf8("GIF89a"))
            .build();
    assertStatus(
        Status.Code.UNAUTHENTICATED,
        "401",
        () -> RecipeImageServiceGrpc.newBlockingStub(channel).uploadRecipeImage(gif));
    assertStatus(
        Status.Code.PERMISSION_DENIED,
        "403",
        () -> imagesAs(plain).uploadRecipeImage(gif));
    assertStatus(Status.Code.INVALID_ARGUMENT, "400", () -> imagesAs(alice).uploadRecipeImage(gif));
    assertStatus(
        Status.Code.INVALID_ARGUMENT,
        "400",
        () -> imagesAs(alice).getRecipeImage(GetRecipeImageRequest.getDefaultInstance()));
    assertEquals(0, images.count());
  }

  // ---- tracing -----------------------------------------------------------------------------

  @Test
  void shouldEchoTheCorrelationIdOnRecipeRpcs() {
    Metadata sent = new Metadata();
    sent.put(AUTHORIZATION, "Bearer " + alice);
    sent.put(CORRELATION_ID, "grpc-recipe-1");
    AtomicReference<Metadata> received = new AtomicReference<>();
    RecipeBookServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            MetadataUtils.newAttachHeadersInterceptor(sent),
            MetadataUtils.newCaptureMetadataInterceptor(received, new AtomicReference<>()))
        .getRecipeBook(GetRecipeBookRequest.getDefaultInstance());
    assertEquals("grpc-recipe-1", received.get().get(CORRELATION_ID));
  }
}
