package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.AiAdvisorSummaryDto;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.FinOpsReportDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
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
    private final FinOpsService finOpsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Autowired
    public AiAdvisorService(@Lazy FinOpsService finOpsService) {
        this.finOpsService = finOpsService;
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<AiAdvisorSummaryDto> getDashboardSummary(String accountId) {
        logger.info("Generating AI dashboard summary for account: {}", accountId);

        return finOpsService.getFinOpsReport(accountId).thenCompose(finOpsReport -> {
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

    @Async("awsTaskExecutor")
    public CompletableFuture<Map<String, String>> getInteractiveResponse(String question, String accountId) {
        logger.info("Generating interactive AI response for account: {}, question: {}", accountId, question);

        return finOpsService.getFinOpsReport(accountId).thenCompose(finOpsReport -> {
            try {
                String contextData = objectMapper.writeValueAsString(finOpsReport);
                String prompt = buildInteractivePrompt(question, contextData);

                return callGeminiApi(prompt).thenApply(responseText -> Map.of("response", responseText.replace("\n", "<br>")));
            } catch (Exception e) {
                logger.error("Error preparing data for AI interactive response for account {}", accountId, e);
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    private String buildInteractivePrompt(String question, String contextJson) {
        return "You are an expert FinOps advisor for a company called XamOps. " +
               "Answer the user's question based on the provided JSON context. " +
               "Be concise and helpful. Do not mention that you are using JSON data. " +
               "Format your response as a single block of text. Use newline characters for paragraphs. " +
               "User Question: " + question + "\n\n" +
               "Context Data: \n" + contextJson;
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
            """.formatted(prompt.replace("\"", "\\\"").replace("\n", "\\n"));

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