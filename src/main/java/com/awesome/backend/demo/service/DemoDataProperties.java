package com.awesome.backend.demo.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시연 데이터 설정.
 *
 * @param dataDir     products.json·orders.json이 있는 디렉토리. 상대 경로라 로컬 실행과
 *                    컨테이너가 같은 값을 쓴다 (Dockerfile이 demo/를 이미지에 넣는다)
 * @param boxStockQty 런을 시작할 때 되돌릴 박스 재고. V2 seed와 같은 값이다
 */
@ConfigurationProperties(prefix = "demo")
public record DemoDataProperties(String dataDir, int boxStockQty) {
}
