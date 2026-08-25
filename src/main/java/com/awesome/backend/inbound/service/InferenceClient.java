package com.awesome.backend.inbound.service;

import com.awesome.backend.inbound.entity.Product;
import java.util.List;

/**
 * 치수 추론 창구. 구현은 Lambda 호출({@link LambdaInferenceClient})과
 * mock({@link MockInferenceClient}) 두 가지이며, {@code INFERENCE_LAMBDA_FUNCTION} 설정 여부로
 * 갈린다 (D-04, D-24).
 *
 * <p>모델은 상품이 아니라 사진 3장을 받는다. 사진이 없으면 실제 추론은 불가능하고,
 * mock 은 사진을 보지 않는다.
 */
public interface InferenceClient {

    /**
     * 실패해도 예외를 던지지 않고 {@link InferenceResult#failed} 를 돌려준다.
     *
     * @param images 카메라 3대분. 비어 있을 수 있다 (데모 이미지가 없는 상품)
     */
    InferenceResult infer(Product product, List<CameraImage> images);
}
