package com.awesome.backend.outbound.api;

import com.awesome.backend.outbound.application.BoxTypeResponse;
import com.awesome.backend.outbound.application.BoxTypeService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 박스 타입 목록 API. docs/02-api-spec.md 3-4.
 */
@RestController
@RequestMapping("/api/v1/box-types")
public class BoxTypeController {

    private final BoxTypeService boxTypeService;

    public BoxTypeController(BoxTypeService boxTypeService) {
        this.boxTypeService = boxTypeService;
    }

    @GetMapping
    public List<BoxTypeResponse> boxTypes() {
        return boxTypeService.findAll();
    }
}
