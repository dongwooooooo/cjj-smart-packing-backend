package com.awesome.backend.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.awesome.backend.inventory.application.StockLocationService.PickInstruction;
import com.awesome.backend.inventory.application.StockLocationService.StockByLocation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SingleLocationStockServiceTest {

    // 실재고 조회는 fake — 위치 서비스 로직만 검증
    private final StockLocationService service =
            new SingleLocationStockService(gtin -> "8801".equals(gtin) ? 5 : 0);

    @Test
    void 전_재고가_가상_단일_위치에_있다() {
        List<StockByLocation> stock = service.stockByLocation("8801");

        assertThat(stock).containsExactly(new StockByLocation("MAIN", 5));
    }

    @Test
    void 피킹_지시는_품목마다_단일_위치를_지정한다() {
        Map<String, Integer> items = new LinkedHashMap<>();
        items.put("8801", 3);
        items.put("8802", 1);

        List<PickInstruction> picks = service.assignPickLocations(7L, items);

        assertThat(picks).containsExactly(
                new PickInstruction("8801", 3, "MAIN"),
                new PickInstruction("8802", 1, "MAIN"));
    }
}
