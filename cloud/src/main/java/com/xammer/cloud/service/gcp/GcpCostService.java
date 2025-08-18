package com.xammer.cloud.service.gcp;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.JobException;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class GcpCostService {

    private final GcpClientProvider gcpClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private static final DateTimeFormatter BQ_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public GcpCostService(GcpClientProvider gcpClientProvider, CloudAccountRepository cloudAccountRepository) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    // ✅ NEW HELPER METHOD
    private Optional<String> getBillingTableForProject(String gcpProjectId) {
        return cloudAccountRepository.findByGcpProjectId(gcpProjectId)
                .map(CloudAccount::getBillingExportTable)
                .filter(table -> table != null && !table.isBlank());
    }

    private CompletableFuture<List<GcpCostDto>> executeCostQuery(String gcpProjectId, String query) {
        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(gcpProjectId);
        if (bqOpt.isEmpty()) {
            log.warn("BigQuery client not available for project {}", gcpProjectId);
            return CompletableFuture.completedFuture(List.of());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query).build();
                TableResult results = bqOpt.get().query(queryConfig);
                List<GcpCostDto> costList = new ArrayList<>();
                for (FieldValueList row : results.iterateAll()) {
                    String name = row.get("name").getStringValue();
                    double totalCost = row.get("total_cost").getDoubleValue();
                    // Anomaly detection is simplified here; a real implementation would use ML.
                    boolean isAnomaly = row.get("is_anomaly") != null && row.get("is_anomaly").getBooleanValue();
                    GcpCostDto dto = new GcpCostDto();
                    dto.setName(name);
                    dto.setAmount(totalCost);
                    dto.setAnomaly(isAnomaly);
                    costList.add(dto);
                }
                return costList;
            } catch (JobException | InterruptedException e) {
                log.error("BigQuery job failed for project {}: {}", gcpProjectId, e.getMessage());
                Thread.currentThread().interrupt();
                return Collections.emptyList();
            }
        });
    }

    public CompletableFuture<List<GcpCostDto>> getCost(String gcpProjectId, String groupBy, String startDateStr, String endDateStr) {
        return getBillingSummary(gcpProjectId);
    }
    
    public CompletableFuture<List<GcpCostDto>> getBillingSummary(String gcpProjectId) {
        Optional<String> billingTableOpt = getBillingTableForProject(gcpProjectId);
        if (billingTableOpt.isEmpty()) {
             log.warn("Billing export table not configured for project {}. Cannot fetch cost data.", gcpProjectId);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.withDayOfMonth(1);

        String query = String.format(
            "SELECT service.description as name, SUM(cost) as total_cost, false as is_anomaly " +
            "FROM `%s` " +
            "WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' " +
            "GROUP BY name HAVING total_cost > 0 ORDER BY total_cost DESC",
            billingTableOpt.get(), startDate.format(BQ_DATE_FORMATTER), endDate.format(BQ_DATE_FORMATTER)
        );
        return executeCostQuery(gcpProjectId, query);
    }

    public CompletableFuture<List<GcpCostDto>> getHistoricalCosts(String gcpProjectId) {
        Optional<String> billingTableOpt = getBillingTableForProject(gcpProjectId);
        if (billingTableOpt.isEmpty()) {
             log.warn("Billing export table not configured for project {}. Cannot fetch historical cost data.", gcpProjectId);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(6).withDayOfMonth(1);
        
        // This query now includes a simplified anomaly check for demonstration.
        String query = String.format(
            "WITH MonthlyCosts AS (" +
            "  SELECT" +
            "    FORMAT_DATE('%%Y-%%m', usage_start_time) as name," +
            "    SUM(cost) as total_cost" +
            "  FROM `%s`" +
            "  WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s'" +
            "  GROUP BY 1" +
            ")," +
            "MonthlyCostsWithLag AS (" +
            "  SELECT" +
            "    name," +
            "    total_cost," +
            "    LAG(total_cost, 1, 0) OVER (ORDER BY name) as prev_month_cost" +
            "  FROM MonthlyCosts" +
            ")" +
            "SELECT name, total_cost, (total_cost > prev_month_cost * 1.2 AND prev_month_cost > 0) as is_anomaly " +
            "FROM MonthlyCostsWithLag ORDER BY name",
            billingTableOpt.get(), startDate.format(BQ_DATE_FORMATTER), endDate.format(BQ_DATE_FORMATTER)
        );
        
        return executeCostQuery(gcpProjectId, query);
    }
}