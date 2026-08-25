package com.awesome.backend.demo.service;

import com.awesome.backend.demo.entity.DemoOrderQueue;
import com.awesome.backend.demo.entity.DemoProduct;
import com.awesome.backend.demo.repository.DemoOrderQueueRepository;
import com.awesome.backend.outbound.entity.Tote;
import com.awesome.backend.outbound.repository.BoxTypeRepository;
import com.awesome.backend.outbound.repository.ToteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새 런 시작 (명세 §4). 서버를 띄운 뒤 이 한 번으로 시연 상태가 만들어진다.
 *
 * <p>전체가 한 트랜잭션이다. 중간에 실패하면 이전 런 상태가 그대로 남는다 —
 * 시연 직전에 반쯤 초기화된 상태로 남는 것이 가장 곤란하다.
 *
 * <p>아무것도 지우지 않는다. 이전 런의 주문·배송단위·대기열은 종결 표시만 하고 남는다.
 */
@Service
public class DemoRunService {

    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final DemoDataLoader loader;
    private final DemoDataProperties properties;
    private final DemoStateResetter resetter;
    private final DemoProductProvisioner provisioner;
    private final DemoOrderQueueRepository queueRepository;
    private final ToteRepository toteRepository;
    private final BoxTypeRepository boxTypeRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DemoRunService(DemoDataLoader loader, DemoDataProperties properties,
                          DemoStateResetter resetter, DemoProductProvisioner provisioner,
                          DemoOrderQueueRepository queueRepository, ToteRepository toteRepository,
                          BoxTypeRepository boxTypeRepository) {
        this.loader = loader;
        this.properties = properties;
        this.resetter = resetter;
        this.provisioner = provisioner;
        this.queueRepository = queueRepository;
        this.toteRepository = toteRepository;
        this.boxTypeRepository = boxTypeRepository;
    }

    @Transactional
    public DemoRunSummary startRun() {
        Path dataDir = Path.of(properties.dataDir());
        List<DemoProductSpec> products = loader.loadProducts(dataDir);
        List<String> batches = loader.loadOrderBatches(dataDir);

        String runId = nextRunId();

        resetter.closePreviousRun();
        resetter.restoreBoxStock(properties.boxStockQty());
        provisioner.provision(products);
        queueBatches(runId, batches);

        return summarize(runId, products, batches.size());
    }

    /**
     * 런 아이디는 초 단위 시각이다 — 사전순으로 정렬하면 시작한 순서가 된다.
     * 같은 초에 두 번 시작하면 뒤에 번호를 붙여 겹치지 않게 한다.
     */
    private String nextRunId() {
        String base = "R" + LocalDateTime.now().format(RUN_ID_FORMAT);
        if (!queueRepository.existsByRunId(base)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = base + "-" + suffix;
            if (!queueRepository.existsByRunId(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * 배치를 대기열에 그대로 담되 주문번호에 런 아이디를 붙인다 (명세 §4-4).
     * 같은 파일로 런을 몇 번 돌려도 주문번호가 겹치지 않고, 파일의 어느 주문인지도 남는다.
     */
    private void queueBatches(String runId, List<String> batches) {
        int seq = 1;
        for (String batch : batches) {
            queueRepository.save(new DemoOrderQueue(runId, seq++, withRunPrefix(runId, batch)));
        }
    }

    private String withRunPrefix(String runId, String batch) {
        try {
            JsonNode root = objectMapper.readTree(batch);
            for (JsonNode order : root.path("orders")) {
                ObjectNode node = (ObjectNode) order;
                node.put("receiptNo", runId + "-" + node.path("receiptNo").asText());
            }
            return root.toString();
        } catch (Exception e) {
            throw new DemoDataException("대기열에 담을 배치를 읽지 못했습니다 — " + e.getMessage(), e);
        }
    }

    private DemoRunSummary summarize(String runId, List<DemoProductSpec> products, int batches) {
        int inbound = (int) products.stream()
                .filter(p -> p.pool() == DemoProduct.Pool.INBOUND).count();
        int idle = toteRepository.findByStatusOrderByIdAsc(Tote.Status.IDLE).size();
        int assigned = toteRepository.findByStatusOrderByIdAsc(Tote.Status.ASSIGNED).size();
        return new DemoRunSummary(runId,
                new DemoRunSummary.Products(inbound, products.size() - inbound),
                batches,
                new DemoRunSummary.Totes(idle, assigned),
                new DemoRunSummary.BoxTypes((int) boxTypeRepository.count(),
                        properties.boxStockQty()));
    }
}
