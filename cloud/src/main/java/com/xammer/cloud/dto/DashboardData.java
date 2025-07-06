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
        private List<CostAnomaly> costAnomalies;
        private List<OptimizationRecommendation> ebsRecommendations;
        private List<OptimizationRecommendation> lambdaRecommendations;
        private ReservationAnalysis reservationAnalysis;
        private List<ReservationPurchaseRecommendation> reservationPurchaseRecommendations;
        private OptimizationSummary optimizationSummary;
        private List<WastedResource> wastedResources;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RegionStatus {
        private String regionName;
        private String regionId;
        private String status;
        private double lat;
        private double lon;
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

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RightsizingRecommendation {
        private String resourceId;
        private String accountName;
        private String region;
        private String service;
        private String currentType;
        private String usage;
        private String recommendedType;
        private double currentMonthlyCost;
        private double recommendedMonthlyCost;
        private double estimatedMonthlySavings;
        private List<RightsizingOption> recommendationOptions;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RightsizingOption {
        private String recommendedType;
        private double recommendedMonthlyCost;
        private double estimatedMonthlySavings;
        private String usage;
    }


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CostAnomaly {
        private String anomalyId;
        private String service;
        private double unexpectedSpend;
        private LocalDate startDate;
        private LocalDate endDate;
    }
    
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ReservationAnalysis {
        private double utilizationPercentage;
        private double coveragePercentage;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ReservationPurchaseRecommendation {
        private String instanceFamily;
        private String recommendedInstances;
        private String recommendedUnits;
        private String minimumUnits;
        private String monthlySavings;
        private String onDemandCost;
        private String estimatedMonthlyCost;
        private String term;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OptimizationSummary {
        private double totalPotentialSavings;
        private long criticalAlertsCount;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class WastedResource {
        private String resourceId;
        private String resourceName;
        private String resourceType;
        private String region;
        private double monthlySavings;
        private String reason;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ServiceGroupDto {
        private String serviceType;
        private List<ResourceDto> resources;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SecurityFinding {
        private String resourceId;
        private String region;
        private String category;
        private String severity;
        private String description;
    }
}
