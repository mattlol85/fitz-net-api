package org.fitznet.fitznetapi.grpc;

import static org.fitznet.fitznetapi.grpc.ProtoMapper.reply;

import io.grpc.stub.StreamObserver;
import org.fitznet.fitznetapi.controller.RecipeImageController;
import org.fitznet.fitznetapi.grpc.proto.GetRecipeImageRequest;
import org.fitznet.fitznetapi.grpc.proto.RecipeImage;
import org.fitznet.fitznetapi.grpc.proto.RecipeImageContent;
import org.fitznet.fitznetapi.grpc.proto.RecipeImageServiceGrpc;
import org.fitznet.fitznetapi.grpc.proto.UploadRecipeImageRequest;
import org.springframework.grpc.server.service.GrpcService;

/**
 * gRPC equivalent of {@link RecipeImageController} (raw bytes instead of base64);
 * {@code LORINET_ACCESS}/{@code ADMIN} is enforced by the controller bean.
 */
@GrpcService
public class RecipeImageGrpcService extends RecipeImageServiceGrpc.RecipeImageServiceImplBase {

  private final RecipeImageController recipeImageController;
  private final GrpcRequestValidator validator;

  public RecipeImageGrpcService(
      RecipeImageController recipeImageController, GrpcRequestValidator validator) {
    this.recipeImageController = recipeImageController;
    this.validator = validator;
  }

  @Override
  public void uploadRecipeImage(
      UploadRecipeImageRequest request, StreamObserver<RecipeImage> observer) {
    reply(
        observer,
        RecipeProtoMapper.toProto(
            recipeImageController.uploadRecipeImage(
                validator.required("mime_type", request.getMimeType()),
                request.getData().toByteArray())));
  }

  @Override
  public void getRecipeImage(
      GetRecipeImageRequest request, StreamObserver<RecipeImageContent> observer) {
    reply(
        observer,
        RecipeProtoMapper.toProto(
            recipeImageController.readImage(validator.required("image_id", request.getImageId()))));
  }
}
