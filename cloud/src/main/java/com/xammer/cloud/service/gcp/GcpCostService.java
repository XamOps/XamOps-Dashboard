package com.xammer.cloud.service.gcp;

import com.google.cloud.bigquery.*;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpCostService {

    private final GcpClientProvider gcpClientProvider;

    @Value("${gcp.billing.export-table-name:}")
    private String billingExportTableName;

    public GcpCostService(GcpClientProvider gcpClientProvider) {
        this.gcpClientProvider = gcpClientProvider;
    }

    /**
     * **[FIXED]** This is the authoritative method to get the gross (unfiltered) MTD spend.
     * It is used by the dashboard and other services to ensure consistency.
     */
    public CompletableFuture<Double> getUnfilteredMonthToDateSpend(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
            if (bqOpt.isEmpty()) return 0.0;

            BigQuery bigquery = bqOpt.get();
            Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
            if (tableNameOpt.isEmpty()) return 0.0;

            LocalDate today = LocalDate.now();
            LocalDate startDate = today.withDayOfMonth(1);

            // This query calculates the gross cost for the current month to date.
            String query = String.format(
                "SELECT SUM(cost) as total_cost FROM `%s` WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s'",
                tableNameOpt.get(),
                startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            );

            try {
                TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
                FieldValue value = results.getValues().iterator().next().get("total_cost");
                if (value.isNull()) {
                    return 0.0;
                }
                return value.getDoubleValue();
            } catch (Exception e) {
                log.error("BigQuery query failed for MTD spend on project {}: {}", gcpProjectId, e.getMessage(), e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return 0.0;
            }
        });
    }

    public CompletableFuture<List<GcpCostDto>> getCostBreakdown(String gcpProjectId, String groupBy) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
            if (bqOpt.isEmpty()) return Collections.emptyList();
            
            String dimension = "service.description";
            if ("PROJECT".equalsIgnoreCase(groupBy)) {
                dimension = "project.name";
            }

            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.withDayOfMonth(1); // MTD for breakdown

            return queryCostData(bqOpt.get(), gcpProjectId, dimension, startDate, endDate);
        });
    }

    public CompletableFuture<List<GcpCostDto>> getBillingSummary(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
            if (bqOpt.isEmpty()) return Collections.emptyList();

            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.withDayOfMonth(1); // MTD for billing summary

            return queryCostData(bqOpt.get(), gcpProjectId, "service.description", startDate, endDate);
        });
    }

    public CompletableFuture<List<GcpCostDto>> getHistoricalCosts(String gcpProjectId) {
         return CompletableFuture.supplyAsync(() -> {
            Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
            if (bqOpt.isEmpty()) return Collections.emptyList();
            
            BigQuery bigquery = bqOpt.get();
            Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
            if (tableNameOpt.isEmpty()) return Collections.emptyList();
            
            LocalDate today = LocalDate.now();
            String endDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
            String startDate = today.minusMonths(6).withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

            // **[FIXED]** Standardized query to use SUM(cost) for gross historical cost.
            String query = String.format(
                "WITH MonthlyCosts AS (SELECT FORMAT_DATE('%%%%Y-%%%%m', usage_start_time) as name, SUM(cost) as total_cost FROM `%s` WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' GROUP BY 1), " +
                "MonthlyCostsWithLag AS (SELECT name, total_cost, LAG(total_cost, 1, 0) OVER (ORDER BY name) as prev_month_cost FROM MonthlyCosts) " +
                "SELECT name, total_cost, (total_cost > prev_month_cost * 1.2 AND prev_month_cost > 10) as is_anomaly FROM MonthlyCostsWithLag ORDER BY name",
                tableNameOpt.get(), startDate, endDate
            );
            
            return executeQuery(bigquery, query, gcpProjectId);
        });
    }

    public CompletableFuture<List<Map<String, Object>>> getDailyCostsForForecast(String gcpProjectId, String serviceName, int days) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
            if (bqOpt.isEmpty()) return Collections.emptyList();

            BigQuery bigquery = bqOpt.get();
            Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
            if (tableNameOpt.isEmpty()) return Collections.emptyList();

            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(days);

            // **[FIXED]** Standardized query to use SUM(cost) for gross daily cost.
            String query = String.format(
                "SELECT FORMAT_DATE('%%Y-%%m-%%d', usage_start_time) as date, SUM(cost) as cost FROM `%s` WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) < '%s' %s GROUP BY 1 ORDER BY 1",
                tableNameOpt.get(), startDate.toString(), endDate.toString(),
                (serviceName != null && !serviceName.isEmpty() && !"ALL".equalsIgnoreCase(serviceName) ? String.format("AND service.description = '%s'", serviceName) : "")
            );

            try {
                TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
                return StreamSupport.stream(results.iterateAll().spliterator(), false)
                    .map(row -> Map.<String, Object>of("date", row.get("date").getStringValue(), "cost", row.get("cost").getDoubleValue()))
                    .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Error fetching daily GCP costs for forecast", e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return Collections.emptyList();
            }
        });
    }
    
    private List<GcpCostDto> queryCostData(BigQuery bigquery, String gcpProjectId, String dimension, LocalDate startDate, LocalDate endDate) {
        Optional<String> tableNameOpt = getBillingTableName(bigquery, gcpProjectId);
        if (tableNameOpt.isEmpty()) return Collections.emptyList();
        
        // **[FIXED]** Standardized query to use SUM(cost) for gross cost. Removed credit calculation.
        String query = String.format(
            "SELECT COALESCE(%s, 'Uncategorized') as name, SUM(cost) as total_cost, false as is_anomaly FROM `%s` WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' GROUP BY 1 HAVING total_cost > 0 ORDER BY total_cost DESC",
            dimension, tableNameOpt.get(), startDate.format(DateTimeFormatter.ISO_LOCAL_DATE), endDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        );
        return executeQuery(bigquery, query, gcpProjectId);
    }

    private List<GcpCostDto> executeQuery(BigQuery bigquery, String query, String gcpProjectId) {
        try {
            log.info("Executing BigQuery query for project {}: {}", gcpProjectId, query);
            TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
            List<GcpCostDto> costList = StreamSupport.stream(results.iterateAll().spliterator(), false)
                .map(row -> new GcpCostDto(row.get("name").getStringValue(), row.get("total_cost").getDoubleValue(), row.get("is_anomaly").getBooleanValue()))
                .collect(Collectors.toList());
            log.info("BigQuery query for project {} returned {} rows.", gcpProjectId, costList.size());
            return costList;
        } catch (Exception e) {
            log.error("BigQuery query failed for project {}: {}", gcpProjectId, e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Collections.emptyList();
        }
    }

    private Optional<String> getBillingTableName(BigQuery bigquery, String gcpProjectId) {
        if (billingExportTableName != null && !billingExportTableName.isBlank()) return Optional.of(billingExportTableName);
        try {
            // This logic dynamically finds the billing export table if not specified.
            for (Dataset dataset : bigquery.listDatasets(gcpProjectId).iterateAll()) {
                for (Table table : bigquery.listTables(dataset.getDatasetId()).iterateAll()) {
                    if (table.getTableId().getTable().startsWith("gcp_billing_export_v1")) {
                        String fullName = String.format("%s.%s.%s", table.getTableId().getProject(), table.getTableId().getDataset(), table.getTableId().getTable());
                        log.info("Dynamically found billing table: {}", fullName);
                        return Optional.of(fullName);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to discover BigQuery billing table for project {}: {}", gcpProjectId, e.getMessage());
        }
        log.warn("Could not find a GCP billing export table for project {}. Please configure gcp.billing.export-table-name.", gcpProjectId);
        return Optional.empty();
    }
}