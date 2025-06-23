package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
public class DashboardData {
    private List<Account> availableAccounts;
    private Account selectedAccount;
    
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Account {
        private String id;
        private String name;
        private List<RegionStatus> regionStatus;
        private ResourceInventory resourceInventory;
        private CloudWatchStatus cloudWatchStatus;
        private List<SecurityInsight> securityInsights;
        private CostHistory costHistory;
        private List<BillingSummary> billingSummary;
        private IamResources iamResources;
        private SavingsSummary savingsSummary;
        private List<OptimizationRecommendation> ec2Recommendations;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RegionStatus {
        private String regionName;
        private String regionId;
        private String status;
        private double top; 
        private double left;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ResourceInventory {
        private int vpc, ecs, ec2, kubernetes, lambdas, ebsVolumes, images, snapshots;
    }
    
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CloudWatchStatus {
        private long ok, alarm, insufficient;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class IamResources {
        private int users, groups, customerManagedPolicies, roles;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class BillingSummary {
        private String serviceName;
        private double monthToDateCost;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SecurityInsight {
        private String title, description, category;
        private int count;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SavingsSummary {
        private double potential;
        private List<SavingsSuggestion> suggestions;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SavingsSuggestion {
        private String service;
        private double suggested;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CostHistory {
        private List<String> labels;
        private List<Double> costs;
    }

    // CORRECTED: Added the 'static' keyword to make this a proper nested DTO class.
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OptimizationRecommendation {
        private String service;
        private String resourceId;
        private String currentType;
        private String recommendedType;
        private double estimatedMonthlySavings;
        private String recommendationReason;
    }

    // CORRECTED: Added the 'static' keyword to make this a proper nested DTO class.
    @Data
    public static class CostAnomaly {
        private String anomalyId;
        private String service;
        private double unexpectedSpend;
        private LocalDate startDate;
        private LocalDate endDate;
    }
}
