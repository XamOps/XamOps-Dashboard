package com.xammer.cloud.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import java.util.Map;

@Service
public class ForecastingService {

    private final RestTemplate restTemplate;
    private final String costApiUrl = "http://localhost:5002/forecast/cost";
    private final String performanceApiUrl = "http://localhost:5002/forecast/performance";

    @Autowired
    public ForecastingService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private String getForecast(String url, Map<String, Object> historicalData) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(historicalData, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            System.err.println("Error calling forecast service at " + url + ": " + e.getResponseBodyAsString());
            return "[]"; // Return empty JSON array on error
        }
    }

    public String getCostForecast(Map<String, Object> historicalData) {
        return getForecast(costApiUrl, historicalData);
    }

    public String getPerformanceForecast(Map<String, Object> historicalData) {
        return getForecast(performanceApiUrl, historicalData);
    }
}