package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceInsightDto {
    private String id;
    private String insight;
    private String description;
    private InsightSeverity severity;
    private InsightCategory category;
    private String account;
    private int quantity;
    private String resourceType;
    private String resourceId;
    private String recommendation;
    private String documentationUrl;
    private double potentialSavings;
    private String region;
    private String createdDate;

    public enum InsightSeverity {
        CRITICAL, WARNING, WEAK_WARNING
    }

    public enum InsightCategory {
        COST, PERFORMANCE, SECURITY, FAULT_TOLERANCE, BEST_PRACTICE // ADDED
    }
}