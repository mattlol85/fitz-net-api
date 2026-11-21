package org.fitznet.fitznetapi.grpc;

import static org.fitznet.fitznetapi.grpc.ProtoMapper.ifPresent;
import static org.fitznet.fitznetapi.grpc.ProtoMapper.optional;

import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Map;
import org.fitznet.fitznetapi.dto.requests.RecipeChapterRequestDto;
import org.fitznet.fitznetapi.dto.requests.RecipeIngredientDto;
import org.fitznet.fitznetapi.dto.requests.RecipeRequestDto;
import org.fitznet.fitznetapi.dto.requests.UpdateRecipeBookRequestDto;
import org.fitznet.fitznetapi.dto.responses.DeleteRecipeResponseDto;
import org.fitznet.fitznetapi.dto.responses.ImageContentDto;
import org.fitznet.fitznetapi.dto.responses.IngredientIndexDto;
import org.fitznet.fitznetapi.dto.responses.RecipeBookDto;
import org.fitznet.fitznetapi.dto.responses.RecipeChapterDto;
import org.fitznet.fitznetapi.dto.responses.RecipeDto;
import org.fitznet.fitznetapi.dto.responses.RecipeImageDto;
import org.fitznet.fitznetapi.dto.responses.RecipeListDto;
import org.fitznet.fitznetapi.dto.responses.RecipeSummaryDto;
import org.fitznet.fitznetapi.grpc.proto.DeleteRecipeResponse;
import org.fitznet.fitznetapi.grpc.proto.IngredientIndex;
import org.fitznet.fitznetapi.grpc.proto.IngredientIndexEntry;
import org.fitznet.fitznetapi.grpc.proto.ListRecipesResponse;
import org.fitznet.fitznetapi.grpc.proto.Recipe;
import org.fitznet.fitznetapi.grpc.proto.RecipeBook;
import org.fitznet.fitznetapi.grpc.proto.RecipeChapter;
import org.fitznet.fitznetapi.grpc.proto.RecipeImage;
import org.fitznet.fitznetapi.grpc.proto.RecipeImageContent;
import org.fitznet.fitznetapi.grpc.proto.RecipeIngredient;
import org.fitznet.fitznetapi.grpc.proto.RecipeInput;
import org.fitznet.fitznetapi.grpc.proto.RecipeRef;
import org.fitznet.fitznetapi.grpc.proto.RecipeSummary;
import org.fitznet.fitznetapi.grpc.proto.UpdateRecipeBookRequest;

/** Conversions between the recipe REST DTOs and their {@code recipes.proto} messages. */
final class RecipeProtoMapper {

  private RecipeProtoMapper() {}

  // ---- book --------------------------------------------------------------------------------

  static RecipeBook toProto(RecipeBookDto dto) {
    RecipeBook.Builder b = RecipeBook.newBuilder().setRecipeCount(dto.recipeCount());
    ifPresent(dto.id(), b::setId);
    ifPresent(dto.title(), b::setTitle);
    ifPresent(dto.coverColor(), b::setCoverColor);
    ifPresent(dto.createdAt(), at -> b.setCreatedAt(ProtoMapper.toProto(at)));
    dto.chapters().forEach(c -> b.addChapters(toProto(c)));
    return b.build();
  }

  static RecipeChapter toProto(RecipeChapterDto dto) {
    RecipeChapter.Builder b = RecipeChapter.newBuilder().setRecipeCount(dto.recipeCount());
    ifPresent(dto.id(), b::setId);
    ifPresent(dto.name(), b::setName);
    ifPresent(dto.tabColor(), b::setTabColor);
    return b.build();
  }

  static UpdateRecipeBookRequestDto fromProto(UpdateRecipeBookRequest request) {
    List<RecipeChapterRequestDto> chapters =
        request.getChaptersList().stream()
            .map(
                c ->
                    new RecipeChapterRequestDto(
                        optional(c.hasId(), c.getId()), c.getName(), c.getTabColor()))
            .toList();
    return new UpdateRecipeBookRequestDto(request.getTitle(), request.getCoverColor(), chapters);
  }

  // ---- recipes -----------------------------------------------------------------------------

  static RecipeSummary toProto(RecipeSummaryDto dto) {
    RecipeSummary.Builder b = RecipeSummary.newBuilder().addAllIngredientNames(dto.ingredientNames());
    ifPresent(dto.id(), b::setId);
    ifPresent(dto.title(), b::setTitle);
    ifPresent(dto.chapterId(), b::setChapterId);
    ifPresent(dto.imageId(), b::setImageId);
    ifPresent(dto.servings(), b::setServings);
    ifPresent(dto.prepMinutes(), b::setPrepMinutes);
    ifPresent(dto.cookMinutes(), b::setCookMinutes);
    ifPresent(dto.createdAt(), at -> b.setCreatedAt(ProtoMapper.toProto(at)));
    return b.build();
  }

  static ListRecipesResponse toProto(RecipeListDto dto) {
    ListRecipesResponse.Builder b = ListRecipesResponse.newBuilder().setTotal(dto.total());
    dto.items().forEach(item -> b.addItems(toProto(item)));
    return b.build();
  }

  static Recipe toProto(RecipeDto dto) {
    Recipe.Builder b =
        Recipe.newBuilder()
            .addAllIngredientNames(dto.ingredientNames())
            .addAllInstructions(dto.instructions());
    ifPresent(dto.id(), b::setId);
    ifPresent(dto.title(), b::setTitle);
    ifPresent(dto.chapterId(), b::setChapterId);
    ifPresent(dto.imageId(), b::setImageId);
    ifPresent(dto.servings(), b::setServings);
    ifPresent(dto.prepMinutes(), b::setPrepMinutes);
    ifPresent(dto.cookMinutes(), b::setCookMinutes);
    ifPresent(dto.createdAt(), at -> b.setCreatedAt(ProtoMapper.toProto(at)));
    ifPresent(dto.updatedAt(), at -> b.setUpdatedAt(ProtoMapper.toProto(at)));
    dto.ingredients().forEach(i -> b.addIngredients(toProto(i)));
    return b.build();
  }

  static RecipeIngredient toProto(RecipeIngredientDto dto) {
    RecipeIngredient.Builder b = RecipeIngredient.newBuilder();
    ifPresent(dto.getQuantity(), b::setQuantity);
    ifPresent(dto.getUnit(), b::setUnit);
    ifPresent(dto.getName(), b::setName);
    ifPresent(dto.getNote(), b::setNote);
    return b.build();
  }

  static RecipeRequestDto fromProto(RecipeInput input) {
    List<RecipeIngredientDto> ingredients =
        input.getIngredientsList().stream()
            .map(
                i ->
                    new RecipeIngredientDto(
                        optional(i.hasQuantity(), i.getQuantity()),
                        optional(i.hasUnit(), i.getUnit()),
                        i.getName(),
                        optional(i.hasNote(), i.getNote())))
            .toList();
    return new RecipeRequestDto(
        input.getTitle(),
        optional(input.hasChapterId(), input.getChapterId()),
        input.hasServings() ? input.getServings() : null,
        input.hasPrepMinutes() ? input.getPrepMinutes() : null,
        input.hasCookMinutes() ? input.getCookMinutes() : null,
        ingredients,
        List.copyOf(input.getInstructionsList()),
        optional(input.hasImageId(), input.getImageId()));
  }

  /** A missing {@code recipe} body is the gRPC form of a null JSON body. */
  static RecipeInput requireInput(boolean present, RecipeInput input) {
    if (!present) {
      throw new RequestValidationException(Map.of("recipe", "must not be null"));
    }
    return input;
  }

  static IngredientIndex toProto(IngredientIndexDto dto) {
    IngredientIndex.Builder b = IngredientIndex.newBuilder();
    dto.entries()
        .forEach(
            e -> {
              IngredientIndexEntry.Builder entry = IngredientIndexEntry.newBuilder().setName(e.name());
              e.recipes()
                  .forEach(
                      r -> {
                        RecipeRef.Builder ref = RecipeRef.newBuilder();
                        ifPresent(r.id(), ref::setId);
                        ifPresent(r.title(), ref::setTitle);
                        entry.addRecipes(ref);
                      });
              b.addEntries(entry);
            });
    return b.build();
  }

  static DeleteRecipeResponse toProto(DeleteRecipeResponseDto dto) {
    return DeleteRecipeResponse.newBuilder().setId(dto.id()).setDeleted(dto.deleted()).build();
  }

  // ---- images ------------------------------------------------------------------------------

  static RecipeImage toProto(RecipeImageDto dto) {
    RecipeImage.Builder b = RecipeImage.newBuilder().setSizeBytes(dto.sizeBytes());
    ifPresent(dto.id(), b::setId);
    ifPresent(dto.mimeType(), b::setMimeType);
    ifPresent(dto.width(), b::setWidth);
    ifPresent(dto.height(), b::setHeight);
    ifPresent(dto.createdAt(), at -> b.setCreatedAt(ProtoMapper.toProto(at)));
    return b.build();
  }

  static RecipeImageContent toProto(ImageContentDto dto) {
    return RecipeImageContent.newBuilder()
        .setMimeType(dto.mimeType())
        .setData(ByteString.copyFrom(dto.data()))
        .build();
  }
}
