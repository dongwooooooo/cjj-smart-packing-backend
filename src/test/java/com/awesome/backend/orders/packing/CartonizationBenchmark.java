package com.awesome.backend.orders.packing;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.demo.service.DemoDataLoader;
import com.awesome.backend.demo.service.DemoProductSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 편성 시간이 배치 크기에 따라 어떻게 늘어나는지 잰다. 기본 테스트에서 빠져 있고
 * {@code ./gradlew benchmark}로만 돈다.
 *
 * <p>슈퍼블록과 시도 다양화는 "배치 계산이 느려지는 게 실측되면 도입"으로 미뤄 둔 상태다
 * (명세 §4-1, §4-2). 그 실측을 여기서 한다 — DB 없이 순수 로직만 돌려 편성 함수 자체의
 * 시간을 본다.
 *
 * <p>상품 풀은 {@code demo/data/products.json}의 11종을 기준 삼아 500 SKU로 부풀린다.
 * 치수는 ±20% 흔들고, 무게는 기준 상품의 밀도(무게/부피)에 흔든 부피를 곱해 만든다 —
 * 치수만 키우고 무게를 그대로 두면 요금 구간 판정이 실제와 어긋난다. 난수 씨앗이 고정이라
 * 같은 입력이 반복 실행에서도 그대로 나온다.
 */
@Tag("benchmark")
class CartonizationBenchmark {

    private static final long SEED = 20260914L;
    private static final int SKU_COUNT = 500;
    private static final int[] BATCH_SIZES = {100, 1_000, 5_000};
    private static final int WARMUP_ORDERS = 200;
    private static final int SLOWEST_SAMPLE = 10;

    private static final Path DATA_DIR = Path.of("demo/data");
    private static final Path REPORT = Path.of("build/reports/cartonization-benchmark.md");

    /** V2 + V12 seed 박스 A~F호. 판두께 0.5cm, 박스 자체 무게 0kg (application.yml·V13 seed). */
    private static final List<CatalogBox> CATALOG = List.of(
            box(1, 22.0, 19.0, 9.0), box(2, 27.0, 18.0, 15.0), box(3, 34.0, 25.0, 21.0),
            box(4, 41.0, 31.0, 28.0), box(5, 48.0, 38.0, 34.0), box(6, 52.0, 48.0, 40.0));

    /** V13 seed 요금 구간표 (CJ대한통운 표준운임 2024, 동일권역). */
    private static final RateTable RATES = new RateTable(List.of(
            new RateTable.Tier(1, "극소형", 80, 2, 5000),
            new RateTable.Tier(2, "소형", 100, 5, 6000),
            new RateTable.Tier(3, "중형", 120, 10, 7000),
            new RateTable.Tier(4, "대형", 140, 15, 8000),
            new RateTable.Tier(5, "특대형", 160, 20, 9000)));

    private static final CarrierLimits LIMITS = new CarrierLimits(160.0, 100.0, 20.0);

    private final PackingEngine engine = new PackingEngine(3.0);
    private final BlockFactory blockFactory = new BlockFactory(1.0);
    private final Cartonizer cartonizer = new Cartonizer(engine, LIMITS);

    private static CatalogBox box(long id, double w, double l, double h) {
        return CatalogBox.of(id, BoxSpec.ofCm(w, l, h), 0.0, 0.5);
    }

    @Test
    void 배치_크기별_편성_시간() throws IOException {
        List<Sku> skus = buildSkuPool();
        int maxBatch = BATCH_SIZES[BATCH_SIZES.length - 1];
        List<Order> orders = buildOrders(skus, maxBatch);

        warmUp(orders);

        List<BatchResult> results = new ArrayList<>();
        for (int size : BATCH_SIZES) {
            results.add(measure(orders.subList(0, size)));
        }

        String report = render(skus, orders, results);
        System.out.println(report);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report);

        assertThat(results).hasSameSizeAs(BATCH_SIZES);
        assertThat(results).allSatisfy(r -> assertThat(r.rejected()).isZero());
    }

    // ---------- 입력 생성 ----------

    /** 시연 상품 11종을 기준으로 치수를 흔들어 500 SKU를 만든다. 무게는 기준 상품의 밀도를 따른다. */
    private List<Sku> buildSkuPool() {
        List<DemoProductSpec> base = new DemoDataLoader().loadProducts(DATA_DIR).stream()
                .filter(p -> p.widthCm() != null && p.weightKg() != null)
                .toList();
        Random random = new Random(SEED);
        List<Sku> skus = new ArrayList<>(SKU_COUNT);
        for (int i = 0; i < SKU_COUNT; i++) {
            DemoProductSpec source = base.get(random.nextInt(base.size()));
            double baseW = source.widthCm().doubleValue();
            double baseL = source.lengthCm().doubleValue();
            double baseH = source.heightCm().doubleValue();
            double density = source.weightKg().doubleValue() / (baseW * baseL * baseH);
            double w = jitter(baseW, random);
            double l = jitter(baseL, random);
            double h = jitter(baseH, random);
            skus.add(new Sku("9%012d".formatted(i), round(w), round(l), round(h),
                    round(w * l * h * density), source.fragile(), source.irregular()));
        }
        return skus;
    }

    /** ±20%. */
    private static double jitter(double value, Random random) {
        return value * (0.8 + 0.4 * random.nextDouble());
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    /** 주문 1건 = 1~10줄, 줄마다 다른 SKU 1~5개. */
    private List<Order> buildOrders(List<Sku> skus, int count) {
        Random random = new Random(SEED + 1);
        List<Order> orders = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int lineCount = 1 + random.nextInt(10);
            Set<Integer> picked = new LinkedHashSet<>();
            while (picked.size() < lineCount) {
                picked.add(random.nextInt(skus.size()));
            }
            List<PackItem> items = new ArrayList<>();
            for (int index : picked) {
                Sku sku = skus.get(index);
                int qty = 1 + random.nextInt(5);
                items.addAll(blockFactory.toItems(sku.gtin(), sku.widthCm(), sku.lengthCm(),
                        sku.heightCm(), sku.weightKg(), sku.fragile(), sku.irregular(), qty));
            }
            orders.add(new Order(lineCount, List.copyOf(items)));
        }
        return orders;
    }

    // ---------- 측정 ----------

    /** JIT가 데워지기 전 값이 첫 배치에 섞이지 않게 앞의 일부를 버린다. */
    private void warmUp(List<Order> orders) {
        for (Order order : orders.subList(0, Math.min(WARMUP_ORDERS, orders.size()))) {
            cartonizer.cartonize(order.items(), CATALOG, RATES);
        }
    }

    private BatchResult measure(List<Order> orders) {
        long[] nanos = new long[orders.size()];
        List<OrderTiming> timings = new ArrayList<>(orders.size());
        int split = 0;
        int rejected = 0;

        long batchStart = System.nanoTime();
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            long started = System.nanoTime();
            int units;
            try {
                units = cartonizer.cartonize(order.items(), CATALOG, RATES).size();
            } catch (OversizedItemException | OverweightItemException e) {
                units = 0;
                rejected++;
            }
            nanos[i] = System.nanoTime() - started;
            if (units >= 2) {
                split++;
            }
            timings.add(new OrderTiming(i, order.lineCount(), order.items().size(), units, nanos[i]));
        }
        long batchNanos = System.nanoTime() - batchStart;

        // 통째 후보 판정은 측정 구간 밖에서 따로 센다 — 측정값에 섞이면 안 된다
        int wholeFits = 0;
        for (Order order : orders) {
            if (fitsWhole(order.items())) {
                wholeFits++;
            }
        }

        long[] sorted = nanos.clone();
        java.util.Arrays.sort(sorted);
        List<OrderTiming> slowest = timings.stream()
                .sorted(Comparator.comparingLong(OrderTiming::nanos).reversed())
                .limit(SLOWEST_SAMPLE)
                .toList();

        return new BatchResult(orders.size(), batchNanos, percentile(sorted, 0.50),
                percentile(sorted, 0.95), sorted[sorted.length - 1], split, wholeFits, rejected,
                slowest);
    }

    /**
     * 주문 전체가 박스 1개에 들어가는가 — Cartonizer의 통째 시도와 같은 판정(치수 수용 +
     * 접수 한도)을 밖에서 다시 한다.
     *
     * <p>적층불가 상품이 섞인 주문은 이 후보가 있어도 그룹이 먼저 갈라져 배송단위가 2개
     * 이상으로 나온다. 지금 상품 풀에는 적층불가가 없어 두 수치가 어긋날 일은 없다.
     */
    private boolean fitsWhole(List<PackItem> items) {
        List<Block> blocks = items.stream().map(PackItem::block).toList();
        double weight = items.stream().mapToDouble(PackItem::weightKg).sum();
        for (CatalogBox candidate : CATALOG) {
            if (engine.canPack(blocks, candidate.spec())
                    && LIMITS.allows(candidate, weight + candidate.tareWeightKg())) {
                return true;
            }
        }
        return false;
    }

    /** 정렬된 표본의 nearest-rank 백분위. */
    private static long percentile(long[] sorted, double ratio) {
        int index = (int) Math.ceil(ratio * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    // ---------- 보고 ----------

    private String render(List<Sku> skus, List<Order> orders, List<BatchResult> results) {
        StringBuilder out = new StringBuilder();
        out.append("# 카토나이제이션 확장성 측정\n\n");
        out.append("측정 시각: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append("\n\n");
        out.append("## 측정 환경\n\n");
        out.append("| 항목 | 값 |\n| --- | --- |\n");
        out.append("| CPU | ").append(cpuModel()).append(" |\n");
        out.append("| 논리 코어 | ").append(Runtime.getRuntime().availableProcessors()).append(" |\n");
        out.append("| JDK | ").append(System.getProperty("java.version")).append(" (")
                .append(System.getProperty("java.vm.name")).append(") |\n");
        out.append("| OS | ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(' ')
                .append(System.getProperty("os.arch")).append(" |\n\n");

        out.append("## 입력\n\n");
        out.append("- SKU ").append(skus.size()).append("종 — 시연 상품 11종의 치수를 ±20% 흔들고,")
                .append(" 무게는 기준 상품의 밀도 × 흔든 부피로 만들었다 (난수 씨앗 고정)\n");
        out.append("- 주문 1건 = 1~10줄, 줄마다 다른 SKU 1~5개. 주문당 평균 낱개 ")
                .append("%.1f".formatted(orders.stream().mapToInt(o -> o.items().size()).average().orElse(0)))
                .append("개, 최대 ")
                .append(orders.stream().mapToInt(o -> o.items().size()).max().orElse(0)).append("개\n");
        out.append("- 박스 A~F호 6종, 요금 5구간, 접수 한도 세변합 160cm / 최장변 100cm / 총무게 20kg\n");
        out.append("- 큰 배치는 작은 배치의 주문을 그대로 포함한다(같은 스트림의 앞부분). 워밍업 ")
                .append(WARMUP_ORDERS).append("건은 버렸다\n");
        out.append("- 단일 스레드, `Cartonizer.cartonize` 호출만 측정 (DB·직렬화 없음)\n\n");

        out.append("## 배치 크기별 결과\n\n");
        out.append("| 주문 수 | 총 시간(ms) | 주문당 평균(ms) | p50(ms) | p95(ms) | 최대(ms) | 분할 주문 | 통째 수용 |\n");
        out.append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (BatchResult r : results) {
            out.append("| ").append(r.orders())
                    .append(" | ").append(ms(r.totalNanos()))
                    .append(" | ").append("%.3f".formatted(r.totalNanos() / 1_000_000.0 / r.orders()))
                    .append(" | ").append(ms(r.p50Nanos()))
                    .append(" | ").append(ms(r.p95Nanos()))
                    .append(" | ").append(ms(r.maxNanos()))
                    .append(" | ").append(r.split()).append(" (")
                    .append("%.1f%%".formatted(100.0 * r.split() / r.orders())).append(")")
                    .append(" | ").append(r.wholeFits()).append(" (")
                    .append("%.1f%%".formatted(100.0 * r.wholeFits() / r.orders())).append(")")
                    .append(" |\n");
        }

        BatchResult largest = results.get(results.size() - 1);
        out.append("\n## 가장 오래 걸린 주문 ").append(SLOWEST_SAMPLE).append("건 (")
                .append(largest.orders()).append("건 배치)\n\n");
        out.append("| 순위 | 주문 번호 | 줄 수 | 낱개 수 | 배송단위 | 시간(ms) |\n");
        out.append("| ---: | ---: | ---: | ---: | ---: | ---: |\n");
        int rank = 1;
        for (OrderTiming t : largest.slowest()) {
            out.append("| ").append(rank++)
                    .append(" | ").append(t.index())
                    .append(" | ").append(t.lineCount())
                    .append(" | ").append(t.itemCount())
                    .append(" | ").append(t.units())
                    .append(" | ").append(ms(t.nanos())).append(" |\n");
        }
        out.append('\n');
        return out.toString();
    }

    private static String ms(long nanos) {
        return "%.3f".formatted(nanos / 1_000_000.0);
    }

    private static String cpuModel() {
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            return "미확인 — macOS 아님";
        }
        try {
            Process process = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string")
                    .redirectErrorStream(true).start();
            String value = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            return value.isEmpty() ? "미확인 — sysctl 응답 없음" : value;
        } catch (IOException | InterruptedException e) {
            return "미확인 — " + e.getClass().getSimpleName();
        }
    }

    // ---------- 값 ----------

    private record Sku(String gtin, double widthCm, double lengthCm, double heightCm,
                       double weightKg, boolean fragile, boolean irregular) {
    }

    private record Order(int lineCount, List<PackItem> items) {
    }

    private record OrderTiming(int index, int lineCount, int itemCount, int units, long nanos) {
    }

    private record BatchResult(int orders, long totalNanos, long p50Nanos, long p95Nanos,
                               long maxNanos, int split, int wholeFits, int rejected,
                               List<OrderTiming> slowest) {
    }
}
