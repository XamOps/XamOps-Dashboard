package com.xammer.cloud.dto.gcp;

import com.xammer.cloud.dto.DashboardData;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class GcpDashboardData {

    // Fields to mirror AWS DashboardData.Account for parity
    private DashboardData.ResourceInventory resourceInventory;
    private DashboardData.IamResources iamResources;
    private int securityScore;
    private List<DashboardData.SecurityInsight> securityInsights;
    private DashboardData.SavingsSummary savingsSummary;

    // Cost and Billing Data
    private double monthToDateSpend;
    private double forecastedSpend;
    private double lastMonthSpend;
    private List<GcpCostDto> costHistory; // DETAILED historical data for the chart
    private List<GcpCostDto> billingSummary; // DETAILED billing data for the table

    // Optimization Data
    private List<GcpOptimizationRecommendation> rightsizingRecommendations;
    private List<GcpWasteItem> wastedResources;
    private DashboardData.OptimizationSummary optimizationSummary;

    // NEWLY ADDED FIELD TO FIX THE ERROR
    private List<DashboardData.RegionStatus> regionStatus;
}