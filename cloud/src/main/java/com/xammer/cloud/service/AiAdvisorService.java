package com.xammer.cloud.service;

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
     * Gathers key dashboard data and uses an LLM to generate a summary.
     * @param accountId The AWS account ID.
     * @return A CompletableFuture containing the natural language summary.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<String> getDashboardSummary(String accountId) {
        logger.info("Generating AI dashboard summary for account: {}", accountId);

        // Fetch all necessary data in parallel
        CompletableFuture<FinOpsReportDto> finOpsReportFuture = awsDataService.getFinOpsReport(accountId);

        return finOpsReportFuture.thenCompose(finOpsReport -> {
            try {
                // Prepare the data for the prompt
                Map<String, Object> promptData = new HashMap<>();
                
                // Add top 3 cost services
                if (finOpsReport.getCostBreakdown() != null && finOpsReport.getCostBreakdown().getByService() != null) {
                    promptData.put("topCostServices", finOpsReport.getCostBreakdown().getByService().stream().limit(3).collect(Collectors.toList()));
                }

                // Add top 3 rightsizing recommendations by savings
                if (finOpsReport.getRightsizingRecommendations() != null) {
                    promptData.put("topRightsizingSavings", finOpsReport.getRightsizingRecommendations().stream()
                        .sorted(Comparator.comparing(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings).reversed())
                        .limit(3).collect(Collectors.toList()));
                }

                // Add top 3 wasted resources by savings
                if (finOpsReport.getWastedResources() != null) {
                    promptData.put("topWastedResources", finOpsReport.getWastedResources().stream()
                        .sorted(Comparator.comparing(DashboardData.WastedResource::getMonthlySavings).reversed())
                        .limit(3).collect(Collectors.toList()));
                }

                // Add KPIs
                promptData.put("keyPerformanceIndicators", finOpsReport.getKpis());

                String jsonData = objectMapper.writeValueAsString(promptData);
                String prompt = buildPrompt(jsonData);

                return callGeminiApi(prompt);

            } catch (Exception e) {
                logger.error("Error preparing data for AI summary for account {}", accountId, e);
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    /**
     * Constructs the prompt to be sent to the Gemini API.
     * @param jsonData The JSON string of data to be analyzed.
     * @return The full prompt string.
     */
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

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=" + geminiApiKey))
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
