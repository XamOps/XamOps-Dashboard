package com.xammer.cloud.controller;

import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.service.CostService;
import com.xammer.cloud.service.ForecastingService;
import com.xammer.cloud.service.PerformanceInsightsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/forecast")
public class ForecastingController {

    private static final Logger logger = LoggerFactory.getLogger(ForecastingController.class);

    @Autowired
    private ForecastingService forecastingService;

    @Autowired
    private CostService costService;

    @Autowired
    private PerformanceInsightsService performanceInsightsService;

    private static final DateTimeFormatter PROPHET_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @GetMapping("/cost")
    public String getCostForecastData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "30") int periods,
            @RequestParam(required = false) String serviceName) {

        try {
            // Fetch more historical data for a better forecast basis (e.g., 3x the forecast period)
            int historicalDays = Math.min(periods * 3, 180);

            HistoricalCostDto historicalCostData = costService.getHistoricalCost(
                accountId,
                "ALL".equalsIgnoreCase(serviceName) ? null : serviceName, // Pass null for "All Services"
                null,
                historicalDays
            ).get();

            if (historicalCostData == null || historicalCostData.getLabels() == null || historicalCostData.getLabels().isEmpty()) {
                logger.warn("No historical cost data found for account {} to generate a forecast.", accountId);
                return "[]";
            }

            logger.info("Historical Data Labels received: " + historicalCostData.getLabels());
            logger.info("Historical Data Costs received: " + historicalCostData.getCosts());

            List<Map<String, Object>> formattedData = new ArrayList<>();

            for (int i = 0; i < historicalCostData.getLabels().size(); i++) {
                Map<String, Object> point = new HashMap<>();
                point.put("ds", historicalCostData.getLabels().get(i));
                point.put("y", historicalCostData.getCosts().get(i));
                formattedData.add(point);
            }

            if (formattedData.isEmpty()) {
                logger.warn("Formatted data for forecasting is empty for account {}", accountId);
                return "[]";
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("periods", periods);
            requestBody.put("data", formattedData);

            return forecastingService.getCostForecast(requestBody);

        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error fetching historical cost data for forecast", e);
            Thread.currentThread().interrupt();
            return "[]";
        }
    }
}