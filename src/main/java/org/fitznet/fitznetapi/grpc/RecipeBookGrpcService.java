package org.fitznet.fitznetapi.grpc;

import static org.fitznet.fitznetapi.grpc.ProtoMapper.reply;

import io.grpc.stub.StreamObserver;
import org.fitznet.fitznetapi.controller.RecipeBookController;
import org.fitznet.fitznetapi.grpc.proto.GetRecipeBookRequest;
import org.fitznet.fitznetapi.grpc.proto.RecipeBook;
import org.fitznet.fitznetapi.grpc.proto.RecipeBookServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.UpdateRecipeBookRequest;
import org.springframework.grpc.server.service.GrpcService;

/**
 * gRPC equivalent of {@link RecipeBookController}; {@code LORINET_ACCESS}/{@code ADMIN} is enforced
 * by the controller bean.
 */
@GrpcService
public class RecipeBookGrpcService extends RecipeBookServiceGrpc.RecipeBookServiceImplBase {

  private final RecipeBookController recipeBookController;
  private final GrpcRequestValidator validator;

  public RecipeBookGrpcService(
      RecipeBookController recipeBookController, GrpcRequestValidator validator) {
    this.recipeBookController = recipeBookController;
    this.validator = validator;
  }

  @Override
  public void getRecipeBook(GetRecipeBookRequest request, StreamObserver<RecipeBook> observer) {
    reply(observer, RecipeProtoMapper.toProto(recipeBookController.getRecipeBook()));
  }

  @Override
  public void updateRecipeBook(UpdateRecipeBookRequest request, StreamObserver<RecipeBook> observer) {
    reply(
        observer,
        RecipeProtoMapper.toProto(
            recipeBookController.updateRecipeBook(
                validator.valid(RecipeProtoMapper.fromProto(request)))));
  }
}
