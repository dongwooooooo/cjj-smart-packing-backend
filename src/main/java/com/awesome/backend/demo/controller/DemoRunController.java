package com.awesome.backend.demo.controller;

import com.awesome.backend.demo.service.DemoRunService;
import com.awesome.backend.demo.service.DemoRunSummary;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 시연 운영용 (명세 §4). admin 경로라 화면에서 부르지 않고 Swagger·curl로만 쓴다. */
@RestController
@RequestMapping("/api/v1/admin/demo")
public class DemoRunController {

    private final DemoRunService demoRunService;

    public DemoRunController(DemoRunService demoRunService) {
        this.demoRunService = demoRunService;
    }

    @PostMapping("/runs")
    public DemoRunSummary startRun() {
        return demoRunService.startRun();
    }
}
