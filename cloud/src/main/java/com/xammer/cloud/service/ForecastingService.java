package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.service.gcp.GcpCostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ForecastingService {

    private final CostService costService;
    private final GcpCostService gcpCostService;

    public ForecastingService(CostService costService, GcpCostService gcpCostService) {
        this.costService = costService;
        this.gcpCostService = gcpCostService;
    }
    
    public CompletableFuture<List<ForecastDto>> getCostForecast(String accountId, String serviceName, int periods) {
        // AWS Implementation
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    public CompletableFuture<List<ForecastDto>> getGcpCostForecast(String gcpProjectId, String serviceName, int periods) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Starting GCP cost forecast for project: {}, service: {}, periods: {}", gcpProjectId, serviceName, periods);
            List<Map<String, Object>> dailyCosts = gcpCostService.getDailyCostsForForecast(gcpProjectId, serviceName, 90).join();
            
            if (dailyCosts.size() < 14) {
                log.warn("Not enough historical data for GCP cost forecast. Found {} days.", dailyCosts.size());
                return Collections.emptyList();
            }

            StringBuilder csvData = new StringBuilder("ds,y\n");
            dailyCosts.forEach(costEntry -> csvData.append(costEntry.get("date")).append(",").append(costEntry.get("cost")).append("\n"));

            try {
                ProcessBuilder pb = new ProcessBuilder("python", "forcasting/forecast_service.py", String.valueOf(periods));
                Process p = pb.start();
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()))) {
                    writer.write(csvData.toString());
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String outputJson = reader.lines().collect(Collectors.joining("\n"));
                    if (p.waitFor() != 0) {
                        try (BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                            log.error("Python script error: {}", errReader.lines().collect(Collectors.joining("\n")));
                        }
                        return Collections.emptyList();
                    }
                    return new ObjectMapper().readValue(outputJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                }
            } catch (Exception e) {
                log.error("Error executing Python forecast script for GCP", e);
                Thread.currentThread().interrupt();
                return Collections.emptyList();
            }
        });
    }
}