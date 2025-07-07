package com.xammer.cloud.dto;

import com.xammer.cloud.dto.DashboardData.BudgetDetails;
import com.xammer.cloud.dto.DashboardData.TaggingCompliance;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinOpsReportDto {

    private Kpis kpis;
    private CostBreakdown costBreakdown;
    private List<DashboardData.OptimizationRecommendation> rightsizingRecommendations;
    private List<DashboardData.WastedResource> wastedResources;
    private List<DashboardData.CostAnomaly> costAnomalies;
    private TaggingCompliance taggingCompliance;
    private List<BudgetDetails> budgets; // ADDED

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Kpis {
        private double monthToDateSpend;
        private double lastMonthSpend;
        private double forecastedSpend;
        private double potentialSavings;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostBreakdown {
        private List<Map<String, Object>> byService;
        private List<Map<String, Object>> byRegion;
    }
}
