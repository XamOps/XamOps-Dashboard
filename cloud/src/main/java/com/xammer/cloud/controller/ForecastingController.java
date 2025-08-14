package com.xammer.cloud.controller;

import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.service.CostService;
import com.xammer.cloud.service.ForecastingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/forecast")
public class ForecastingController {

    private static final Logger logger = LoggerFactory.getLogger(ForecastingController.class);

    private final ForecastingService forecastingService;
    private final CostService costService;

    @Autowired
    public ForecastingController(ForecastingService forecastingService, CostService costService) {
        this.forecastingService = forecastingService;
        this.costService = costService;
    }

    @GetMapping("/cost")
    public CompletableFuture<ResponseEntity<String>> getCostForecastData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "30") int periods,
            @RequestParam(required = false) String serviceName,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        int historicalDays = Math.min(periods * 3, 180);

        return costService.getHistoricalCost(
                accountId,
                "ALL".equalsIgnoreCase(serviceName) ? null : serviceName,
                null,
                historicalDays,
                forceRefresh
        ).thenApply(historicalCostData -> {
            if (historicalCostData == null || historicalCostData.getLabels() == null || historicalCostData.getLabels().isEmpty()) {
                logger.warn("No historical cost data found for account {} to generate a forecast.", accountId);
                return ResponseEntity.ok("[]");
            }

            List<Map<String, Object>> formattedData = new ArrayList<>();
            for (int i = 0; i < historicalCostData.getLabels().size(); i++) {
                Map<String, Object> point = new HashMap<>();
                point.put("ds", historicalCostData.getLabels().get(i));
                point.put("y", historicalCostData.getCosts().get(i));
                formattedData.add(point);
            }

            if (formattedData.isEmpty()) {
                logger.warn("Formatted data for forecasting is empty for account {}", accountId);
                return ResponseEntity.ok("[]");
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("periods", periods);
            requestBody.put("data", formattedData);
            
            try {
                String forecastResult = forecastingService.getCostForecast(requestBody);
                return ResponseEntity.ok(forecastResult);
            } catch (ResourceAccessException e) {
                logger.error("Forecasting service is unavailable at the moment. Skipping forecast generation.", e);
                return ResponseEntity.ok("[]");
            }

        }).exceptionally(ex -> {
            logger.error("Error fetching historical cost data for forecast for account {}", accountId, ex);
            return ResponseEntity.status(500).body("[]");
        });
    }
}