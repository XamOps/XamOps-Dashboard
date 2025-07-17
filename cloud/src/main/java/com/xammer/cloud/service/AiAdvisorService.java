package com.xammer.cloud.service;

import com.xammer.cloud.dto.AiAdvisorSummaryDto;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class AiAdvisorService {

    private static final Logger logger = LoggerFactory.getLogger(AiAdvisorService.class);
    private final AwsDataService awsDataService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    public AiAdvisorService(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    /**
     * Gathers key dashboard data and uses an LLM to generate a structured summary with actions.
     * @param accountId The AWS account ID.
     * @return A CompletableFuture containing the structured AI summary.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<AiAdvisorSummaryDto> getDashboardSummary(String accountId) {
        logger.info("Generating AI dashboard summary for account: {}", accountId);

        CompletableFuture<FinOpsReportDto> finOpsReportFuture = awsDataService.getFinOpsReport(accountId);

        return finOpsReportFuture.thenCompose(finOpsReport -> {
            try {
                Map<String, Object> promptData = new HashMap<>();
                
                if (finOpsReport.getCostBreakdown() != null && finOpsReport.getCostBreakdown().getByService() != null) {
                    promptData.put("topCostServices", finOpsReport.getCostBreakdown().getByService().stream().limit(3).collect(Collectors.toList()));
                }

                if (finOpsReport.getRightsizingRecommendations() != null) {
                    promptData.put("topRightsizingSavings", finOpsReport.getRightsizingRecommendations().stream()
                        .sorted(Comparator.comparing(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings).reversed())
                        .limit(3).collect(Collectors.toList()));
                }

                if (finOpsReport.getWastedResources() != null) {
                    promptData.put("topWastedResources", finOpsReport.getWastedResources().stream()
                        .sorted(Comparator.comparing(DashboardData.WastedResource::getMonthlySavings).reversed())
                        .limit(3).collect(Collectors.toList()));
                }

                promptData.put("keyPerformanceIndicators", finOpsReport.getKpis());

                String jsonData = objectMapper.writeValueAsString(promptData);
                String prompt = buildPrompt(jsonData);

                return callGeminiApi(prompt).thenApply(summaryText -> {
                    List<AiAdvisorSummaryDto.SuggestedAction> actions = new ArrayList<>();
                    if (finOpsReport.getRightsizingRecommendations() != null && !finOpsReport.getRightsizingRecommendations().isEmpty()) {
                        actions.add(new AiAdvisorSummaryDto.SuggestedAction("Review Rightsizing", "navigate", "/rightsizing"));
                    }
                    if (finOpsReport.getWastedResources() != null && !finOpsReport.getWastedResources().isEmpty()) {
                         actions.add(new AiAdvisorSummaryDto.SuggestedAction("Analyze Waste", "navigate", "/waste"));
                    }
                     if (finOpsReport.getCostAnomalies() != null && !finOpsReport.getCostAnomalies().isEmpty()) {
                        actions.add(new AiAdvisorSummaryDto.SuggestedAction("Investigate Anomalies", "navigate", "/finops"));
                    }
                    return new AiAdvisorSummaryDto(summaryText, actions);
                });

            } catch (Exception e) {
                logger.error("Error preparing data for AI summary for account {}", accountId, e);
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    private String buildPrompt(String jsonData) {
        return "You are an expert FinOps advisor for a company called XamOps. " +
               "Your tone is professional, concise, and helpful. " +
               "Based on the following JSON data, provide a brief summary for a cloud manager. " +
               "The summary should be formatted as a single block of text using newline characters for paragraphs. " +
               "Start with a general statement about the account's financial health based on the KPIs. " +
               "Then, highlight the most significant cost driver. " +
               "Finally, point out the single biggest savings opportunity, combining insights from both rightsizing and wasted resources. " +
               "Do not use markdown formatting like headers or lists. " +
               "Data: \n" + jsonData;
    }

    /**
     * Calls the Gemini API with the provided prompt.
     * @param prompt The prompt to send.
     * @return A CompletableFuture with the API's text response.
     */
    private CompletableFuture<String> callGeminiApi(String prompt) {
        try {
            String requestBody = """
                {
                  "contents": [{
                    "parts":[{
                      "text": "%s"
                    }]
                  }]
                }
            """.formatted(prompt.replace("\"", "\\\""));

            // *** FIXED: Changed model from "gemini-pro" to "gemini-1.5-flash-latest" to resolve 404 error ***
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=" + geminiApiKey))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            return HttpClient.newHttpClient()
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            logger.error("Gemini API call failed with status {}: {}", response.statusCode(), response.body());
                            throw new RuntimeException("AI Advisor API call failed.");
                        }
                        return parseGeminiResponse(response.body());
                    });

        } catch (Exception e) {
            logger.error("Failed to call Gemini API", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Parses the JSON response from the Gemini API to extract the text content.
     * @param responseBody The JSON response body from the API.
     * @return The extracted text summary.
     */
    private String parseGeminiResponse(String responseBody) {
        try {
            Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseMap.get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                if (content != null) {
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                    if (parts != null && !parts.isEmpty()) {
                        return (String) parts.get(0).get("text");
                    }
                }
            }
            logger.warn("Could not parse text from Gemini response: {}", responseBody);
            return "Could not generate a summary at this time.";
        } catch (Exception e) {
            logger.error("Failed to parse Gemini response body", e);
            return "Error parsing AI advisor response.";
        }
    }
}
