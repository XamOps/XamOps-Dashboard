package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.service.ForecastingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/gcp/forecast")
public class GcpForecastingController {

    private final ForecastingService forecastingService;

    public GcpForecastingController(ForecastingService forecastingService) {
        this.forecastingService = forecastingService;
    }

    @GetMapping("/cost")
    public CompletableFuture<ResponseEntity<List<ForecastDto>>> getCostForecast(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "30") int periods,
            @RequestParam(required = false, defaultValue = "ALL") String serviceName) {
        
        return forecastingService.getGcpCostForecast(accountId, serviceName, periods)
                .thenApply(ResponseEntity::ok);
    }
}