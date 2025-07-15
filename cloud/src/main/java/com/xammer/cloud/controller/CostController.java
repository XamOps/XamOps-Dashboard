package com.xammer.cloud.controller;

import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.service.CostService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/costs")
public class CostController {

    private final CostService costService;

    public CostController(CostService costService) {
        this.costService = costService;
    }

    @GetMapping("/breakdown")
    public ResponseEntity<List<CostDto>> getCostBreakdown(
            @RequestParam String accountId,
            @RequestParam String groupBy,
            @RequestParam(required = false) String tag) throws ExecutionException, InterruptedException {
        
        List<CostDto> costData = costService.getCostBreakdown(accountId, groupBy, tag).get();
        return ResponseEntity.ok(costData);
    }
}
