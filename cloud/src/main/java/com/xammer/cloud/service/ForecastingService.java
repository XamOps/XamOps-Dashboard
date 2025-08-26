package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.service.gcp.GcpCostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ForecastingService {

    private final CostService costService;
    private final GcpCostService gcpCostService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ForecastingService(CostService costService, GcpCostService gcpCostService, RestTemplate restTemplate) {
        this.costService = costService;
        this.gcpCostService = gcpCostService;
        this.restTemplate = restTemplate;
    }

    public CompletableFuture<List<ForecastDto>> getCostForecast(String accountId, String serviceName, int periods) {
        int historicalDays = Math.min(periods * 3, 180); // Fetch more historical data for better accuracy

        return costService.getHistoricalCost(accountId, "ALL".equalsIgnoreCase(serviceName) ? null : serviceName, null, historicalDays, false)
            .thenCompose(historicalCostData -> {
                if (historicalCostData == null || historicalCostData.getLabels() == null || historicalCostData.getLabels().size() < 14) {
                    log.warn("Not enough historical cost data for AWS account {} to generate a forecast (found {} days).", accountId, historicalCostData != null && historicalCostData.getLabels() != null ? historicalCostData.getLabels().size() : 0);
                    return CompletableFuture.completedFuture(Collections.emptyList());
                }

                List<Map<String, Object>> formattedData = new ArrayList<>();
                for (int i = 0; i < historicalCostData.getLabels().size(); i++) {
                    Map<String, Object> point = new HashMap<>();
                    point.put("ds", historicalCostData.getLabels().get(i));
                    point.put("y", historicalCostData.getCosts().get(i));
                    formattedData.add(point);
                }

                try {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    Map<String, Object> requestBody = new HashMap<>();
                    requestBody.put("data", formattedData);
                    requestBody.put("periods", periods);

                    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

String forecastJson = restTemplate.postForObject("http://forecast:5002/forecast/cost", entity, String.class);
                    
                    List<ForecastDto> forecast = objectMapper.readValue(forecastJson, new TypeReference<>() {});
                    return CompletableFuture.completedFuture(forecast);

                } catch (Exception e) {
                    log.error("Error calling Python forecast service for AWS account {}", accountId, e);
                    return CompletableFuture.completedFuture(Collections.emptyList());
                }
            });
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