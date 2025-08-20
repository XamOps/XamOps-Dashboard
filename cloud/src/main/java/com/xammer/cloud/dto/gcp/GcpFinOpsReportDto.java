package com.xammer.cloud.dto.gcp;

import com.xammer.cloud.dto.DashboardData;
import lombok.Data;
import java.util.List;

@Data
public class GcpFinOpsReportDto {

    private List<GcpCostDto> billingSummary;
    private List<GcpCostDto> costHistory;
    private List<GcpOptimizationRecommendation> rightsizingRecommendations;
    private List<GcpWasteItem> wastedResources;
    private DashboardData.OptimizationSummary optimizationSummary;
    
    private double monthToDateSpend;
    private double lastMonthSpend;
    private double forecastedSpend;
}