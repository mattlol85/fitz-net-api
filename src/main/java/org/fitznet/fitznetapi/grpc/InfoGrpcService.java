package org.fitznet.fitznetapi.grpc;

import static org.fitznet.fitznetapi.grpc.ProtoMapper.reply;

import io.grpc.stub.StreamObserver;
import java.util.Map;
import org.fitznet.fitznetapi.controller.ApiInfoController;
import org.fitznet.fitznetapi.grpc.proto.GetInfoRequest;
import org.fitznet.fitznetapi.grpc.proto.GetInfoResponse;
import org.fitznet.fitznetapi.grpc.proto.InfoServiceGrpc;
import org.springframework.grpc.server.service.GrpcService;

/** gRPC equivalent of {@link ApiInfoController}. */
@GrpcService
public class InfoGrpcService extends InfoServiceGrpc.InfoServiceImplBase {

  private final ApiInfoController apiInfoController;

  public InfoGrpcService(ApiInfoController apiInfoController) {
    this.apiInfoController = apiInfoController;
  }

  @Override
  public void getInfo(GetInfoRequest request, StreamObserver<GetInfoResponse> observer) {
    Map<String, Object> info = apiInfoController.getApiInfo();
    reply(
        observer,
        GetInfoResponse.newBuilder()
            .setStatus(String.valueOf(info.get("status")))
            .setCollectionCount(((Number) info.get("collectionCount")).intValue())
            .setVersion(String.valueOf(info.get("version")))
            .build());
  }
}
