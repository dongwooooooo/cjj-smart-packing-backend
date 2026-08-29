package com.awesome.backend.demo.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시연 데이터 설정.
 *
 * @param dataDir     products.json·orders.json이 있는 디렉토리. 상대 경로라 로컬 실행과
 *                    컨테이너가 같은 값을 쓴다 (Dockerfile이 demo/를 이미지에 넣는다)
 * @param boxStockQty 런을 시작할 때 되돌릴 박스 재고. V2 seed와 같은 값이다
 * @param prereleasedBatches 리셋 끝에 미리 투입할 주문 묶음 수. 시연을 시작하면 라인마다 포장할
 *                           배송단위가 이미 놓여 있어야 한다 — 빈 화면에서 시작하면 보여줄 게 없다.
 *                           남은 묶음은 시연 도중 Load 로 넣어 "주문이 들어왔다" 장면을 만든다.
 * @param warmupInference 리셋 끝에 추론을 한 번 깨울지. Lambda 콜드 스타트(10초)가 시연 첫
 *                        장면을 실패로 보이게 하는 것을 막는다
 */
@ConfigurationProperties(prefix = "demo")
public record DemoDataProperties(String dataDir, int boxStockQty, int prereleasedBatches,
                                 boolean warmupInference) {
}
