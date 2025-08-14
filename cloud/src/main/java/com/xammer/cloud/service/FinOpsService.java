package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.budgets.BudgetsClient;
import software.amazon.awssdk.services.budgets.model.*;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.Anomaly;
import software.amazon.awssdk.services.costexplorer.model.AnomalyDateInterval;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.GetAnomaliesRequest;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.Granularity;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinition;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinitionType;
import software.amazon.awssdk.services.costexplorer.model.RootCause;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class FinOpsService {

    private static final Logger logger = LoggerFactory.getLogger(FinOpsService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final CloudListService cloudListService;
    private final OptimizationService optimizationService;
    private final List<String> requiredTags;
    private final DatabaseCacheService dbCache;

    @Autowired
    public FinOpsService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            @Lazy CloudListService cloudListService,
            @Lazy OptimizationService optimizationService,
            @Value("${tagging.compliance.required-tags}") List<String> requiredTags,
            DatabaseCacheService dbCache) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.cloudListService = cloudListService;
        this.optimizationService = optimizationService;
        this.requiredTags = requiredTags;
        this.dbCache = dbCache;
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<FinOpsReportDto> getFinOpsReport(String accountId, boolean forceRefresh) {
        String cacheKey = "finopsReport-" + accountId;
        if (!forceRefresh) {
            Optional<FinOpsReportDto> cachedData = dbCache.get(cacheKey, FinOpsReportDto.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CloudAccount account = getAccount(accountId);
        return cloudListService.getRegionStatusForAccount(account, forceRefresh).thenCompose(activeRegions -> {
            logger.info("--- LAUNCHING ASYNC DATA FETCH FOR FINOPS REPORT for account {}---", account.getAwsAccountId());

            CompletableFuture<List<DashboardData.BillingSummary>> billingSummaryFuture = getBillingSummary(account, forceRefresh);
            CompletableFuture<List<DashboardData.WastedResource>> wastedResourcesFuture = optimizationService.getWastedResources(account, activeRegions, forceRefresh);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> rightsizingFuture = optimizationService.getAllOptimizationRecommendations(accountId, forceRefresh);
            CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = getCostAnomalies(account, forceRefresh);
            CompletableFuture<DashboardData.CostHistory> costHistoryFuture = getCostHistory(account, forceRefresh);
            CompletableFuture<DashboardData.TaggingCompliance> taggingComplianceFuture = getTaggingCompliance(account, forceRefresh);
            CompletableFuture<List<DashboardData.BudgetDetails>> budgetsFuture = getAccountBudgets(account, forceRefresh);
            CompletableFuture<List<Map<String, Object>>> costByRegionFuture = getCostByRegion(account, forceRefresh);

            return CompletableFuture.allOf(billingSummaryFuture, wastedResourcesFuture, rightsizingFuture, anomaliesFuture, costHistoryFuture, taggingComplianceFuture, budgetsFuture, costByRegionFuture)
                    .thenApply(v -> {
                        logger.info("--- ALL FINOPS DATA FETCHES COMPLETE, AGGREGATING NOW ---");

                        List<DashboardData.BillingSummary> billingSummary = billingSummaryFuture.join();
                        DashboardData.CostHistory costHistory = costHistoryFuture.join();

                        double mtdSpend = billingSummary.stream().mapToDouble(DashboardData.BillingSummary::getMonthToDateCost).sum();
                        double lastMonthSpend = 0.0;
                        if (costHistory.getLabels() != null && costHistory.getLabels().size() > 1) {
                            int lastMonthIndex = costHistory.getLabels().size() - 2;
                            if (lastMonthIndex >= 0 && lastMonthIndex < costHistory.getCosts().size()) {
                                lastMonthSpend = costHistory.getCosts().get(lastMonthIndex);
                            }
                        }
                        double daysInMonth = YearMonth.now().lengthOfMonth();
                        double currentDayOfMonth = LocalDate.now().getDayOfMonth();
                        double forecastedSpend = (currentDayOfMonth > 0) ? (mtdSpend / currentDayOfMonth) * daysInMonth : 0;
                        double rightsizingSavings = rightsizingFuture.join().stream().mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings).sum();
                        double wasteSavings = wastedResourcesFuture.join().stream().mapToDouble(DashboardData.WastedResource::getMonthlySavings).sum();
                        double totalPotentialSavings = rightsizingSavings + wasteSavings;
                        
                        FinOpsReportDto.Kpis kpis = new FinOpsReportDto.Kpis(mtdSpend, lastMonthSpend, forecastedSpend, totalPotentialSavings);
                        
                        List<Map<String, Object>> costByService = billingSummary.stream().sorted(Comparator.comparingDouble(DashboardData.BillingSummary::getMonthToDateCost).reversed()).limit(10).map(s -> Map.<String, Object>of("service", s.getServiceName(), "cost", s.getMonthToDateCost())).collect(Collectors.toList());
                        
                        FinOpsReportDto.CostBreakdown costBreakdown = new FinOpsReportDto.CostBreakdown(costByService, costByRegionFuture.join());

                        FinOpsReportDto report = new FinOpsReportDto(kpis, costBreakdown, rightsizingFuture.join(), wastedResourcesFuture.join(), anomaliesFuture.join(), taggingComplianceFuture.join(), budgetsFuture.join());
                        dbCache.put(cacheKey, report);
                        return report;
                    });
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.BudgetDetails>> getAccountBudgets(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "budgets-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.BudgetDetails>> cachedData = dbCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }
        
        BudgetsClient budgetsClient = awsClientProvider.getBudgetsClient(account);
        logger.info("FinOps Scan: Fetching account budgets for account {}...", account.getAwsAccountId());
        try {
            DescribeBudgetsRequest request = DescribeBudgetsRequest.builder().accountId(account.getAwsAccountId()).build();
            List<Budget> budgets = budgetsClient.describeBudgets(request).budgets();
            List<DashboardData.BudgetDetails> result = budgets.stream().map(b -> new DashboardData.BudgetDetails(
                            b.budgetName(), b.budgetLimit().amount(), b.budgetLimit().unit(),
                            b.calculatedSpend() != null ? b.calculatedSpend().actualSpend().amount() : BigDecimal.ZERO,
                            b.calculatedSpend() != null && b.calculatedSpend().forecastedSpend() != null ? b.calculatedSpend().forecastedSpend().amount() : BigDecimal.ZERO
                    )).collect(Collectors.toList());
            dbCache.put(cacheKey, result);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error("Failed to fetch AWS Budgets for account {}", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    public void createBudget(String accountId, DashboardData.BudgetDetails budgetDetails) {
        CloudAccount account = getAccount(accountId);
        BudgetsClient budgetsClient = awsClientProvider.getBudgetsClient(account);
        logger.info("Creating new budget: {} for account {}", budgetDetails.getBudgetName(), account.getAwsAccountId());
        try {
            Budget budget = Budget.builder()
                    .budgetName(budgetDetails.getBudgetName()).budgetType(BudgetType.COST).timeUnit(TimeUnit.MONTHLY)
                    .timePeriod(TimePeriod.builder().start(Instant.now()).build())
                    .budgetLimit(Spend.builder().amount(budgetDetails.getBudgetLimit()).unit(budgetDetails.getBudgetUnit()).build()).build();
            CreateBudgetRequest request = CreateBudgetRequest.builder().accountId(account.getAwsAccountId()).budget(budget).build();
            budgetsClient.createBudget(request);
            dbCache.evict("budgets-" + accountId); 
        } catch (Exception e) {
            logger.error("Failed to create AWS Budget '{}' for account {}", budgetDetails.getBudgetName(), account.getAwsAccountId(), e);
            throw new RuntimeException("Failed to create budget", e);
        }
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.TaggingCompliance> getTaggingCompliance(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "taggingCompliance-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.TaggingCompliance> cachedData = dbCache.get(cacheKey, DashboardData.TaggingCompliance.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        logger.info("FinOps Scan: Checking tagging compliance for account {}...", account.getAwsAccountId());
        return cloudListService.getAllResources(account, forceRefresh).thenApply(allResources -> {
            List<DashboardData.UntaggedResource> untaggedList = new ArrayList<>();
            int taggedCount = 0;

            for (ResourceDto resource : allResources) {
                List<String> missingTags = new ArrayList<>(this.requiredTags);
                Map<String, String> resourceTags = new HashMap<>();
                if (resource.getDetails() != null) {
                     resource.getDetails().forEach((key, value) -> {
                        if (key.toLowerCase().startsWith("tag:")) {
                            resourceTags.put(key.substring(4), value);
                        }
                    });
                }
                missingTags.removeAll(resourceTags.keySet());
                if (missingTags.isEmpty()) {
                    taggedCount++;
                } else {
                    untaggedList.add(new DashboardData.UntaggedResource(resource.getId(), resource.getType(), resource.getRegion(), missingTags));
                }
            }

            int totalScanned = allResources.size();
            double percentage = (totalScanned > 0) ? ((double) taggedCount / totalScanned) * 100.0 : 100.0;
            DashboardData.TaggingCompliance result = new DashboardData.TaggingCompliance(percentage, totalScanned, untaggedList.size(), untaggedList.stream().limit(20).collect(Collectors.toList()));
            dbCache.put(cacheKey, result);
            return result;
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<Map<String, Object>>> getCostByTag(String accountId, String tagKey, boolean forceRefresh) {
        String cacheKey = "costByTag-" + accountId + "-" + tagKey;
        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cachedData = dbCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }
        
        CloudAccount account = getAccount(accountId);
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching month-to-date cost by tag: {} for account {}", tagKey, accountId);
        if (tagKey == null || tagKey.isBlank()) return CompletableFuture.completedFuture(Collections.emptyList());
        try {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(LocalDate.now().withDayOfMonth(1).toString())
                            .end(LocalDate.now().plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY).metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.TAG).key(tagKey).build()).build();
            
            List<Map<String, Object>> result = ce.getCostAndUsage(request).resultsByTime()
                .stream().flatMap(r -> r.groups().stream())
                .map(g -> {
                    String tagValue = g.keys().get(0).isEmpty() ? "Untagged" : g.keys().get(0);
                    double cost = Double.parseDouble(g.metrics().get("UnblendedCost").amount());
                    return Map.<String, Object>of("tagValue", tagValue, "cost", cost);
                })
                .filter(map -> (double) map.get("cost") > 0.01)
                .collect(Collectors.toList());
            dbCache.put(cacheKey, result);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error("Could not fetch cost by tag key '{}' for account {}. This tag may not be activated in the billing console.", tagKey, e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<Map<String, Object>>> getCostByRegion(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "costByRegion-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cachedData = dbCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching month-to-date cost by region for account {}...", account.getAwsAccountId());
        try {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(LocalDate.now().withDayOfMonth(1).toString()).end(LocalDate.now().plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY).metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("REGION").build()).build();
            List<Map<String, Object>> result = ce.getCostAndUsage(request).resultsByTime()
                    .stream().flatMap(r -> r.groups().stream())
                    .map(g -> {
                        String region = g.keys().get(0).isEmpty() ? "Unknown" : g.keys().get(0);
                        double cost = Double.parseDouble(g.metrics().get("UnblendedCost").amount());
                        return Map.<String, Object>of("region", region, "cost", cost);
                    })
                    .filter(map -> (double) map.get("cost") > 0.01)
                    .collect(Collectors.toList());
            dbCache.put(cacheKey, result);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error("Could not fetch cost by region for account {}", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
    
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.BillingSummary>> getBillingSummary(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "billingSummary-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.BillingSummary>> cachedData = dbCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching billing summary for account {}...", account.getAwsAccountId());
        try {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(LocalDate.now().withDayOfMonth(1).toString()).end(LocalDate.now().plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY).metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("SERVICE").build()).build();
            List<DashboardData.BillingSummary> result = ce.getCostAndUsage(request).resultsByTime()
                    .stream().flatMap(r -> r.groups().stream())
                    .map(g -> new DashboardData.BillingSummary(g.keys().get(0), Double.parseDouble(g.metrics().get("UnblendedCost").amount())))
                    .filter(s -> s.getMonthToDateCost() > 0.01).collect(Collectors.toList());
            dbCache.put(cacheKey, result);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error("Could not fetch billing summary for account {}", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.CostHistory> getCostHistory(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "costHistory-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.CostHistory> cachedData = dbCache.get(cacheKey, DashboardData.CostHistory.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching cost history for account {}...", account.getAwsAccountId());
        List<String> labels = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        List<Boolean> anomalies = new ArrayList<>();
        try {
            for (int i = 5; i >= 0; i--) {
                LocalDate month = LocalDate.now().minusMonths(i);
                labels.add(month.format(DateTimeFormatter.ofPattern("MMM uuuu")));
                GetCostAndUsageRequest req = GetCostAndUsageRequest.builder()
                        .timePeriod(DateInterval.builder().start(month.withDayOfMonth(1).toString()).end(month.plusMonths(1).withDayOfMonth(1).toString()).build())
                        .granularity(Granularity.MONTHLY).metrics("UnblendedCost").build();
                double currentCost = Double.parseDouble(ce.getCostAndUsage(req).resultsByTime().get(0).total().get("UnblendedCost").amount());
                costs.add(currentCost);

                boolean isAnomaly = false;
                if (costs.size() > 1) {
                    double previousCost = costs.get(costs.size() - 2);
                    if (previousCost > 100) {
                        double changePercent = ((currentCost - previousCost) / previousCost) * 100;
                        if (changePercent > 20) {
                            isAnomaly = true;
                        }
                    }
                }
                anomalies.add(isAnomaly);
            }
        } catch (Exception e) {
            logger.error("Could not fetch cost history for account {}", account.getAwsAccountId(), e);
        }
        
        DashboardData.CostHistory result = new DashboardData.CostHistory(labels, costs, anomalies);
        dbCache.put(cacheKey, result);
        return CompletableFuture.completedFuture(result);
    }
    
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.CostAnomaly>> getCostAnomalies(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "costAnomalies-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.CostAnomaly>> cachedData = dbCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching cost anomalies for account {}...", account.getAwsAccountId());
        try {
            AnomalyDateInterval dateInterval = AnomalyDateInterval.builder()
                    .startDate(LocalDate.now().minusDays(60).toString())
                    .endDate(LocalDate.now().minusDays(1).toString()).build();
            GetAnomaliesRequest request = GetAnomaliesRequest.builder().dateInterval(dateInterval).build();
            List<Anomaly> anomalies = ce.getAnomalies(request).anomalies();
            List<DashboardData.CostAnomaly> result = anomalies.stream()
                    .map(a -> new DashboardData.CostAnomaly(
                            a.anomalyId(), getServiceNameFromAnomaly(a), a.impact().totalImpact(),
                            LocalDate.parse(a.anomalyStartDate().substring(0, 10)),
                            a.anomalyEndDate() != null ? LocalDate.parse(a.anomalyEndDate().substring(0, 10)) : LocalDate.now()
                    ))
                    .collect(Collectors.toList());
            dbCache.put(cacheKey, result);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error("Could not fetch Cost Anomalies for account {}. This might be a permissions issue or the service is not enabled.", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
    
    private String getServiceNameFromAnomaly(Anomaly anomaly) {
        if (anomaly.rootCauses() != null && !anomaly.rootCauses().isEmpty()) {
            RootCause rootCause = anomaly.rootCauses().get(0);
            if (rootCause.service() != null) return rootCause.service();
        }
        return "Unknown Service";
    }
}