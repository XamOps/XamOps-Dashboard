package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for a structured response from the AI Advisor.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiAdvisorSummaryDto {

    private String summary;
    private List<SuggestedAction> suggestedActions;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedAction {
        private String text; // e.g., "Review Cost Anomalies"
        private String actionType; // e.g., "navigate"
        private String actionValue; // e.g., "/finops"
    }
}
