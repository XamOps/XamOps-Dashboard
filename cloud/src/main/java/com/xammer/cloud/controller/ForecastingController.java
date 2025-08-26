package com.xammer.cloud.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.service.ForecastingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/forecast")
public class ForecastingController {

    private static final Logger logger = LoggerFactory.getLogger(ForecastingController.class);

    private final ForecastingService forecastingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ForecastingController(ForecastingService forecastingService) {
        this.forecastingService = forecastingService;
    }

    @GetMapping("/cost")
    public CompletableFuture<ResponseEntity<String>> getCostForecastData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "30") int periods,
            @RequestParam(required = false, defaultValue = "ALL") String serviceName) {

        return forecastingService.getCostForecast(accountId, serviceName, periods)
            .thenApply(forecastResult -> {
                try {
                    String jsonResult = objectMapper.writeValueAsString(forecastResult);
                    return ResponseEntity.ok(jsonResult);
                } catch (Exception e) {
                    logger.error("Error serializing forecast DTO to JSON for account {}", accountId, e);
                    return ResponseEntity.status(500).body("[]");
                }
            })
            .exceptionally(ex -> {
                logger.error("Error generating cost forecast for account {}", accountId, ex);
                return ResponseEntity.status(500).body("[]");
            });
    }
}