package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
public class DashboardData {
    private List<Account> availableAccounts;
    private Account selectedAccount;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ResourceInventory {
        private int vpc;
        private int ecs;
        private int ec2;
        private int kubernetes;
        private int lambdas;
        private int ebsVolumes;
        private int images;
        private int snapshots;
        private int s3Buckets;
        private int rdsInstances;
        private int route53Zones;
        private int loadBalancers;
    }
    
    @Data @AllArgsConstructor @NoArgsConstructor public static class ServiceQuotaInfo { private String serviceName; private String quotaName; private double limit; private double usage; private String status; }
    @Data @AllArgsConstructor @NoArgsConstructor public static class BudgetDetails { private String budgetName; private BigDecimal budgetLimit; private String budgetUnit; private BigDecimal actualSpend; private BigDecimal forecastedSpend; }
    @Data @AllArgsConstructor @NoArgsConstructor public static class TaggingCompliance { private double compliancePercentage; private int totalResourcesScanned; private int untaggedResourcesCount; private List<UntaggedResource> untaggedResources; }
    @Data @AllArgsConstructor @NoArgsConstructor public static class UntaggedResource { private String resourceId; private String resourceType; private String region; private List<String> missingTags; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class Account { private String id; private String name; private List<RegionStatus> regionStatus; private ResourceInventory resourceInventory; private CloudWatchStatus cloudWatchStatus; private List<SecurityInsight> securityInsights; private CostHistory costHistory; private List<BillingSummary> billingSummary; private IamResources iamResources; private SavingsSummary savingsSummary; private List<OptimizationRecommendation> ec2Recommendations; private List<CostAnomaly> costAnomalies; private List<OptimizationRecommendation> ebsRecommendations; private List<OptimizationRecommendation> lambdaRecommendations; private ReservationAnalysis reservationAnalysis; private List<ReservationPurchaseRecommendation> reservationPurchaseRecommendations; private OptimizationSummary optimizationSummary; private List<WastedResource> wastedResources; private List<ServiceQuotaInfo> serviceQuotas; private int securityScore; private double monthToDateSpend; private double forecastedSpend; private double lastMonthSpend;}
    @Data 
@NoArgsConstructor 
public static class RegionStatus {
    private String regionId;
    private String regionName;
    private String status;
    private double latitude;
    private double longitude;
    private String activeServices;
    
    public RegionStatus(String regionId, String regionName, String status, 
                       double latitude, double longitude, String activeServices) {
        this.regionId = regionId;
        this.regionName = regionName;
        this.status = status;
        this.latitude = latitude;
        this.longitude = longitude;
        this.activeServices = activeServices;
    }
    
    public RegionStatus(String regionId, String regionName, String status, 
                       double latitude, double longitude) {
        this(regionId, regionName, status, latitude, longitude, null);
    }
}

    @Data @NoArgsConstructor @AllArgsConstructor public static class CloudWatchStatus { private long ok; private long alarm; private long insufficient; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class IamResources { private int users; private int groups; private int customerManagedPolicies; private int roles; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class BillingSummary { private String serviceName; private double monthToDateCost; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class SecurityInsight { private String title; private String description; private String category; private int count; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class SavingsSummary { private double potential; private List<SavingsSuggestion> suggestions; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class SavingsSuggestion { private String service; private double suggested; }
    @Data @NoArgsConstructor @AllArgsConstructor public static class CostHistory { private List<String> labels; private List<Double> costs; private List<Boolean> anomalies; }
    
    @Data @AllArgsConstructor @NoArgsConstructor 
    public static class OptimizationRecommendation { 
        private String service; 
        private String resourceId; 
        private String currentType; 
        private String recommendedType; 
        private double estimatedMonthlySavings; 
        private String recommendationReason; 
        private double currentMonthlyCost;
        private double recommendedMonthlyCost;
    }

    @Data @AllArgsConstructor @NoArgsConstructor public static class RightsizingRecommendation { private String resourceId; private String accountName; private String region; private String service; private String currentType; private String usage; private String recommendedType; private double currentMonthlyCost; private double recommendedMonthlyCost; private double estimatedMonthlySavings; private List<RightsizingOption> recommendationOptions; }
    @Data @AllArgsConstructor @NoArgsConstructor public static class RightsizingOption { private String recommendedType; private double recommendedMonthlyCost; private double estimatedMonthlySavings; private String usage; }
    @Data @AllArgsConstructor @NoArgsConstructor public static class CostAnomaly { private String anomalyId; private String service; private double unexpectedSpend; private LocalDate startDate; private LocalDate endDate; }
    @Data @AllArgsConstructor @NoArgsConstructor public static class ReservationAnalysis { private double utilizationPercentage; private double coveragePercentage; }
    @Data @AllArgsConstructor @NoArgsConstructor public static class ReservationPurchaseRecommendation { private String instanceFamily; private String recommendedInstances; private String recommendedUnits; private String minimumUnits; private String monthlySavings; private String onDemandCost; private String estimatedMonthlyCost; private String term; }
    @Data @AllArgsConstructor @NoArgsConstructor public static class OptimizationSummary { private double totalPotentialSavings; private long criticalAlertsCount; }
    @Data @AllArgsConstructor @NoArgsConstructor public static class WastedResource { private String resourceId; private String resourceName; private String resourceType; private String region; private double monthlySavings; private String reason; }
    @Data @AllArgsConstructor @NoArgsConstructor public static class ServiceGroupDto { private String serviceType; private List<ResourceDto> resources; }
    @Data @AllArgsConstructor @NoArgsConstructor public static class SecurityFinding { private String resourceId; private String region; private String category; private String severity; private String description; private String complianceFramework; private String controlId; }
}
