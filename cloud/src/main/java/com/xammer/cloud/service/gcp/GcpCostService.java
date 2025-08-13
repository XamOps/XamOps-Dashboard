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
                    boolean isAnomaly = row.get("is_anomaly") != null && row.get("is_anomaly").getBooleanValue(); // Read anomaly flag
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
                return List.of();
            }
        });
    }

    public CompletableFuture<List<GcpCostDto>> getCost(String gcpProjectId, String groupBy, String startDateStr, String endDateStr) {
        return getBillingSummary(gcpProjectId);
    }
    
    public CompletableFuture<List<GcpCostDto>> getBillingSummary(String gcpProjectId) {
        Optional<String> billingTableOpt = getBillingTableForProject(gcpProjectId);
        if (billingTableOpt.isEmpty()) {
             log.warn("Billing export table not configured for project {}", gcpProjectId);
            return CompletableFuture.completedFuture(List.of());
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
             log.warn("Billing export table not configured for project {}", gcpProjectId);
            return CompletableFuture.completedFuture(List.of());
        }
        
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(6).withDayOfMonth(1);
        
        String query = String.format(
            "WITH DailyCosts AS (" +
            "  SELECT" +
            "    DATE(usage_start_time) AS day," +
            "    SUM(cost) AS daily_cost" +
            "  FROM `%s`" +
            "  WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s'" +
            "  GROUP BY 1" +
            "), AnomalyModel AS (" +
            "  SELECT *" +
            "  FROM ML.DETECT_ANOMALIES(MODEL `bqml_models.daily_cost_arima_plus`," + // Assuming a pre-trained model
            "    (SELECT day as time_series_timestamp, daily_cost as time_series_data FROM DailyCosts)," +
            "    STRUCT(0.95 AS anomaly_prob_threshold))" +
            "), MonthlyCosts AS (" +
            "  SELECT" +
            "    FORMAT_DATE('%%Y-%%m', day) as name," +
            "    SUM(daily_cost) as total_cost," +
            "    MAX(CASE WHEN is_anomaly THEN 1 ELSE 0 END) as is_anomaly_flag" +
            "  FROM AnomalyModel" +
            "  GROUP BY 1" +
            ")" +
            "SELECT name, total_cost, is_anomaly_flag as is_anomaly FROM MonthlyCosts ORDER BY name",
            billingTableOpt.get(), startDate.format(BQ_DATE_FORMATTER), endDate.format(BQ_DATE_FORMATTER)
        );
        
        // This is a simplified anomaly detection to mirror the previous logic.
        // A full implementation would require training a model first.
        // The previous code had a hardcoded anomaly check. It is replaced here with a query that assumes a model exists.
        
        return executeCostQuery(gcpProjectId, query);
    }
}