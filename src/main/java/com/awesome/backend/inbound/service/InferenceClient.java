package com.awesome.backend.inbound.service;

import com.awesome.backend.inbound.entity.Product;

/**
 * 치수 추론 창구. 구현은 외부 모델 서버 호출({@link HttpInferenceClient})과
 * mock({@link MockInferenceClient}) 두 가지이며, {@code INFERENCE_API_URL} 설정 여부로 갈린다 (D-04).
 *
 * <p>AI팀 모델 서버가 준비되기 전에도 입고 플로우 전체가 동작해야 한다 (docs/05 §2 P1).
 */
public interface InferenceClient {

    /** 실패해도 예외를 던지지 않고 {@link InferenceResult#failed} 를 돌려준다. */
    InferenceResult infer(Product product);
}
