package com.awesome.backend.demo.service;

import java.util.List;

/**
 * 지금 시연이 어떤 상태인지 (명세 §4). 리셋 직후든 시연 중이든 같은 눈으로 본다.
 *
 * @param products 풀별 상품. 입고 풀은 치수가 비어 있고 재고가 0인 게 정상이다
 * @param batches  대기열. 투입된 배치와 남은 배치가 순서대로 보인다
 * @param progress 접수된 주문과 만들어진 배송단위 수
 * @param auto     자동 투입이 돌고 있는지와 그 간격
 * @param summary  위 내용을 사람이 읽을 여러 줄 텍스트로 요약한 것
 */
public record DemoStatus(List<PoolProducts> products, List<Batch> batches, Totes totes,
                         BoxTypes boxTypes, Progress progress, Auto auto, String summary) {

    /** @param intervalSeconds 돌고 있지 않으면 null */
    public record Auto(boolean running, Integer intervalSeconds) {
    }

    public record PoolProducts(String pool, List<Item> items) {

        public record Item(String gtin, String name, String dimStatus, int stockQty) {
        }
    }

    public record Batch(int seq, int orderCount, boolean released) {
    }

    public record Totes(int idle, int assigned) {
    }

    public record BoxTypes(int count, int stockQty) {
    }

    public record Progress(long orders, long shipments) {
    }
}
