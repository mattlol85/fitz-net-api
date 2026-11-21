package org.fitznet.fitznetapi.grpc;

import static org.fitznet.fitznetapi.grpc.ProtoMapper.optional;
import static org.fitznet.fitznetapi.grpc.ProtoMapper.reply;

import io.grpc.stub.StreamObserver;
import org.fitznet.fitznetapi.controller.RecipeController;
import org.fitznet.fitznetapi.grpc.proto.CreateRecipeRequest;
import org.fitznet.fitznetapi.grpc.proto.DeleteRecipeRequest;
import org.fitznet.fitznetapi.grpc.proto.DeleteRecipeResponse;
import org.fitznet.fitznetapi.grpc.proto.GetIngredientIndexRequest;
import org.fitznet.fitznetapi.grpc.proto.GetRecipeRequest;
import org.fitznet.fitznetapi.grpc.proto.IngredientIndex;
import org.fitznet.fitznetapi.grpc.proto.ListRecipesRequest;
import org.fitznet.fitznetapi.grpc.proto.ListRecipesResponse;
import org.fitznet.fitznetapi.grpc.proto.Recipe;
import org.fitznet.fitznetapi.grpc.proto.RecipeServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.UpdateRecipeRequest;
import org.springframework.grpc.server.service.GrpcService;

/**
 * gRPC equivalent of {@link RecipeController}; {@code LORINET_ACCESS}/{@code ADMIN} is enforced by
 * the controller bean.
 */
@GrpcService
public class RecipeGrpcService extends RecipeServiceGrpc.RecipeServiceImplBase {

  private final RecipeController recipeController;
  private final GrpcRequestValidator validator;

  public RecipeGrpcService(RecipeController recipeController, GrpcRequestValidator validator) {
    this.recipeController = recipeController;
    this.validator = validator;
  }

  @Override
  public void listRecipes(ListRecipesRequest request, StreamObserver<ListRecipesResponse> observer) {
    reply(
        observer,
        RecipeProtoMapper.toProto(
            recipeController.listRecipes(
                request.getIngredientsList(),
                optional(request.hasQ(), request.getQ()),
                optional(request.hasChapterId(), request.getChapterId()))));
  }

  @Override
  public void getIngredientIndex(
      GetIngredientIndexRequest request, StreamObserver<IngredientIndex> observer) {
    reply(observer, RecipeProtoMapper.toProto(recipeController.getIngredientIndex()));
  }

  @Override
  public void getRecipe(GetRecipeRequest request, StreamObserver<Recipe> observer) {
    reply(
        observer,
        RecipeProtoMapper.toProto(
            recipeController.getRecipe(validator.required("recipe_id", request.getRecipeId()))));
  }

  @Override
  public void createRecipe(CreateRecipeRequest request, StreamObserver<Recipe> observer) {
    RecipeProtoMapper.requireInput(request.hasRecipe(), request.getRecipe());
    reply(
        observer,
        RecipeProtoMapper.toProto(
            recipeController.createRecipe(
                validator.valid(RecipeProtoMapper.fromProto(request.getRecipe())))));
  }

  @Override
  public void updateRecipe(UpdateRecipeRequest request, StreamObserver<Recipe> observer) {
    String id = validator.required("recipe_id", request.getRecipeId());
    RecipeProtoMapper.requireInput(request.hasRecipe(), request.getRecipe());
    reply(
        observer,
        RecipeProtoMapper.toProto(
            recipeController.updateRecipe(
                id, validator.valid(RecipeProtoMapper.fromProto(request.getRecipe())))));
  }

  @Override
  public void deleteRecipe(DeleteRecipeRequest request, StreamObserver<DeleteRecipeResponse> observer) {
    reply(
        observer,
        RecipeProtoMapper.toProto(
            recipeController.deleteRecipe(validator.required("recipe_id", request.getRecipeId()))));
  }
}
