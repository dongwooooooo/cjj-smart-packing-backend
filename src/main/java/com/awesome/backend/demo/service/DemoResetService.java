package com.awesome.backend.demo.service;

import com.awesome.backend.demo.entity.DemoOrderQueue;
import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoOrderQueueRepository;
import com.awesome.backend.demo.repository.DemoProductRepository;
import com.awesome.backend.inbound.entity.Product;
import com.awesome.backend.inbound.repository.ProductRepository;
import com.awesome.backend.orders.repository.OrderRepository;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.repository.BoxTypeRepository;
import com.awesome.backend.outbound.repository.ShipmentRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시연 리셋과 상태 조회 (명세 §4).
 *
 * <p>리셋은 지난 시연의 잔여물을 지우고 시연 시작 상태를 만든다. 몇 번을 눌러도 같은
 * 상태가 된다 — 리허설을 반복하려고 만든 것이라 그래야 쓸모가 있다.
 *
 * <p>전체가 한 트랜잭션이다. 중간에 실패하면 이전 상태가 그대로 남는다 —
 * 시연 직전에 반쯤 지워진 상태로 남는 것이 가장 곤란하다.
 */
@Service
public class DemoResetService {

    private final DemoDataLoader loader;
    private final DemoDataProperties properties;
    private final DemoStateResetter resetter;
    private final DemoProductProvisioner provisioner;
    private final DemoOrderQueueRepository queueRepository;
    private final DemoProductRepository demoProductRepository;
    private final ProductRepository productRepository;
    private final ToteRepository toteRepository;
    private final BoxTypeRepository boxTypeRepository;
    private final OrderRepository orderRepository;
    private final ShipmentRepository shipmentRepository;
    private final DemoAutoFeeder autoFeeder;
    private final DemoOrderFeeder orderFeeder;
    private final DemoPrepacker prepacker;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DemoResetService(DemoDataLoader loader, DemoDataProperties properties,
                            DemoStateResetter resetter, DemoProductProvisioner provisioner,
                            DemoOrderQueueRepository queueRepository,
                            DemoProductRepository demoProductRepository,
                            ProductRepository productRepository, ToteRepository toteRepository,
                            BoxTypeRepository boxTypeRepository, OrderRepository orderRepository,
                            ShipmentRepository shipmentRepository, DemoAutoFeeder autoFeeder,
                            DemoOrderFeeder orderFeeder, DemoPrepacker prepacker) {
        this.loader = loader;
        this.properties = properties;
        this.resetter = resetter;
        this.provisioner = provisioner;
        this.queueRepository = queueRepository;
        this.demoProductRepository = demoProductRepository;
        this.productRepository = productRepository;
        this.toteRepository = toteRepository;
        this.boxTypeRepository = boxTypeRepository;
        this.orderRepository = orderRepository;
        this.shipmentRepository = shipmentRepository;
        this.autoFeeder = autoFeeder;
        this.orderFeeder = orderFeeder;
        this.prepacker = prepacker;
    }

    @Transactional
    public DemoResetSummary reset() {
        Path dataDir = Path.of(properties.dataDir());
        // 파일부터 읽는다 — 잘못된 파일이면 아무것도 지우기 전에 멈춘다
        List<DemoProductSpec> products = loader.loadProducts(dataDir);
        List<String> batches = loader.loadOrderBatches(dataDir);

        resetter.clearDemoData();
        resetter.restoreEquipment(properties.boxStockQty());
        provisioner.provision(products);
        queueBatches(batches);
        int released = prerelease(batches.size());
        prepacker.prepack(properties.prepackedShipments());

        int inbound = (int) products.stream()
                .filter(p -> p.pool() == DemoProduct.Pool.INBOUND).count();
        int idle = countTotes(Tote.Status.IDLE);
        int assigned = countTotes(Tote.Status.ASSIGNED);
        int boxes = (int) boxTypeRepository.count();
        return new DemoResetSummary(
                new DemoResetSummary.Products(inbound, products.size() - inbound),
                batches.size(),
                new DemoResetSummary.Totes(idle, assigned),
                new DemoResetSummary.BoxTypes(boxes, properties.boxStockQty()),
                summaryText(inbound, products.size() - inbound, batches.size(), released,
                        idle, assigned, boxes, properties.boxStockQty(),
                        (int) orderRepository.count(), (int) shipmentRepository.count()));
    }

    @Transactional(readOnly = true)
    public DemoStatus status() {
        List<DemoStatus.PoolProducts> pools = new ArrayList<>();
        for (DemoProduct.Pool pool : DemoProduct.Pool.values()) {
            List<DemoStatus.PoolProducts.Item> items = new ArrayList<>();
            for (DemoProduct demo : demoProductRepository.findByPool(pool)) {
                productRepository.findByGtin(demo.gtin()).ifPresent(product ->
                        items.add(new DemoStatus.PoolProducts.Item(product.gtin(), product.name(),
                                product.dimStatus(), product.stockQty())));
            }
            items.sort((a, b) -> a.gtin().compareTo(b.gtin()));
            pools.add(new DemoStatus.PoolProducts(pool.name(), items));
        }

        List<DemoStatus.Batch> batches = queueRepository.findAllByOrderBySeqAsc().stream()
                .map(queued -> new DemoStatus.Batch(queued.seq(), orderCount(queued),
                        queued.releasedAt() != null))
                .toList();

        int idle = countTotes(Tote.Status.IDLE);
        int assigned = countTotes(Tote.Status.ASSIGNED);
        int boxes = (int) boxTypeRepository.count();
        int boxStock = boxTypeRepository.findAll().stream()
                .mapToInt(box -> box.stockQty()).min().orElse(0);
        long orders = orderRepository.count();
        long shipments = shipmentRepository.count();
        int inbound = poolSize(pools, DemoProduct.Pool.INBOUND);
        int outbound = poolSize(pools, DemoProduct.Pool.OUTBOUND);
        int waiting = (int) batches.stream().filter(b -> !b.released()).count();

        return new DemoStatus(pools, batches,
                new DemoStatus.Totes(idle, assigned),
                new DemoStatus.BoxTypes(boxes, boxStock),
                new DemoStatus.Progress(orders, shipments),
                new DemoStatus.Auto(autoFeeder.isRunning(), autoFeeder.intervalSeconds()),
                summaryText(inbound, outbound, batches.size(), batches.size() - waiting,
                        idle, assigned, boxes, boxStock, orders, shipments));
    }

    /**
     * 사람이 읽을 요약. 시연 중에는 Swagger 응답 화면을 그대로 읽으므로,
     * 필드를 눈으로 짜맞추지 않아도 상태가 한 번에 들어오게 만든다.
     */
    private String summaryText(int inbound, int outbound, int batches, int releasedBatches,
                               int idleTotes, int assignedTotes, int boxes, int boxStock,
                               long orders, long shipments) {
        StringBuilder text = new StringBuilder();
        text.append("상품 %d종 — 입고 풀 %d(치수 미확정) / 출고 풀 %d(치수 확정, 재고 세팅)"
                .formatted(inbound + outbound, inbound, outbound));
        text.append("\n대기 배치 %d개 중 %d개 투입".formatted(batches, releasedBatches));
        text.append("\n토트 %d/%d 유휴".formatted(idleTotes, idleTotes + assignedTotes));
        text.append("\n박스 %d종 각 %d개".formatted(boxes, boxStock));
        text.append("\n접수된 주문 %d건, 배송단위 %d개".formatted(orders, shipments));
        return text.toString();
    }

    /**
     * 리셋 끝에 주문 묶음 몇 개를 미리 투입한다.
     *
     * <p>시연을 시작하면 라인마다 포장할 배송단위가 이미 놓여 있어야 한다 — 빈 화면에서
     * 시작하면 보여줄 게 없다. 남긴 묶음은 시연 도중 화면의 Load 로 넣어 주문이 들어오는
     * 장면을 만든다.
     *
     * @return 실제로 투입된 묶음 수. 설정값이 파일의 묶음 수보다 크면 있는 만큼만 넣는다.
     */
    private int prerelease(int totalBatches) {
        int target = Math.min(Math.max(properties.prereleasedBatches(), 0), totalBatches);
        int released = 0;
        for (int i = 0; i < target; i++) {
            if (orderFeeder.feedNext().isEmpty()) {
                break;
            }
            released++;
        }
        return released;
    }

    private void queueBatches(List<String> batches) {
        int seq = 1;
        for (String batch : batches) {
            queueRepository.save(new DemoOrderQueue(seq++, batch));
        }
    }

    private int orderCount(DemoOrderQueue queued) {
        try {
            JsonNode root = objectMapper.readTree(queued.batchJson());
            return root.path("orders").size();
        } catch (Exception e) {
            throw new DemoDataException("대기열의 배치를 읽지 못했습니다 — seq " + queued.seq(), e);
        }
    }

    private int poolSize(List<DemoStatus.PoolProducts> pools, DemoProduct.Pool pool) {
        return pools.stream().filter(p -> p.pool().equals(pool.name()))
                .findFirst().map(p -> p.items().size()).orElse(0);
    }

    private int countTotes(Tote.Status status) {
        return toteRepository.findByStatusOrderByIdAsc(status).size();
    }
}
