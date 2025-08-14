package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinition;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinitionType;
import software.amazon.awssdk.services.costexplorer.model.Granularity;
import software.amazon.awssdk.services.costexplorer.model.Expression;
import software.amazon.awssdk.services.costexplorer.model.Dimension;
import software.amazon.awssdk.services.costexplorer.model.DimensionValues;
import software.amazon.awssdk.services.costexplorer.model.ResultByTime;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class CostService {

    private static final Logger logger = LoggerFactory.getLogger(CostService.class);
    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;

    @Autowired
    private DatabaseCacheService dbCache; // Inject the new database cache service

    public CostService(AwsClientProvider awsClientProvider, CloudAccountRepository cloudAccountRepository) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<CostDto>> getCostBreakdown(String accountId, String groupBy, String tagKey, boolean forceRefresh) {
        String cacheKey = "costBreakdown-" + accountId + "-" + groupBy + "-" + tagKey;
        if (!forceRefresh) {
            Optional<List<CostDto>> cachedData = dbCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        logger.info("Fetching cost breakdown for account {}, grouped by: {}", accountId, groupBy);
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        CostExplorerClient costExplorerClient = awsClientProvider.getCostExplorerClient(account);

        try {
            GroupDefinition groupDefinition;
            if ("TAG".equalsIgnoreCase(groupBy) && tagKey != null && !tagKey.isBlank()) {
                groupDefinition = GroupDefinition.builder().type(GroupDefinitionType.TAG).key(tagKey).build();
            } else {
                groupDefinition = GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key(groupBy).build();
            }

            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(LocalDate.now().withDayOfMonth(1).toString())
                            .end(LocalDate.now().plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost")
                    .groupBy(groupDefinition)
                    .build();

            List<CostDto> result = costExplorerClient.getCostAndUsage(request).resultsByTime()
                    .stream().flatMap(r -> r.groups().stream())
                    .map(g -> new CostDto(g.keys().get(0),
                            Double.parseDouble(g.metrics().get("UnblendedCost").amount())))
                    .filter(s -> s.getAmount() > 0.01)
                    .collect(Collectors.toList());
            
            dbCache.put(cacheKey, result); // Save to database cache
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error("Could not fetch cost breakdown for account {}.", accountId, e);
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<HistoricalCostDto> getHistoricalCost(
            String accountId, String serviceName, String regionName, int days, boolean forceRefresh) {
        String cacheKey = "historicalCost-" + accountId + "-" + serviceName + "-" + regionName + "-" + days;
        if (!forceRefresh) {
            Optional<HistoricalCostDto> cachedData = dbCache.get(cacheKey, HistoricalCostDto.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        logger.info("Fetching historical cost for account {}, service: {}, region: {}, days: {}",
                accountId, serviceName, regionName, days);
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        CostExplorerClient ceClient = awsClientProvider.getCostExplorerClient(account);

        List<String> labels = new ArrayList<>();
        List<Double> costs = new ArrayList<>();

        try {
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(days);
            Expression.Builder filterBuilder = Expression.builder();
            List<Expression> andExpressions = new ArrayList<>();

            if (serviceName != null && !serviceName.isBlank()) {
                andExpressions.add(Expression.builder()
                        .dimensions(DimensionValues.builder().key(Dimension.SERVICE).values(serviceName).build())
                        .build());
            }
            if (regionName != null && !regionName.isBlank()) {
                andExpressions.add(Expression.builder()
                        .dimensions(DimensionValues.builder().key(Dimension.REGION).values(regionName).build())
                        .build());
            }

            Expression finalFilter = null;
            if (!andExpressions.isEmpty()) {
                finalFilter = andExpressions.size() == 1 ? andExpressions.get(0) : Expression.builder().and(andExpressions).build();
            }

            GetCostAndUsageRequest.Builder requestBuilder = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(startDate.toString()).end(endDate.plusDays(1).toString()).build())
                    .granularity(Granularity.DAILY)
                    .metrics("UnblendedCost");

            if (finalFilter != null) {
                requestBuilder.filter(finalFilter);
            }
            GetCostAndUsageRequest request = requestBuilder.build();

            for (ResultByTime result : ceClient.getCostAndUsage(request).resultsByTime()) {
                labels.add(LocalDate.parse(result.timePeriod().start()).format(DateTimeFormatter.ISO_LOCAL_DATE));
                double cost = Double.parseDouble(result.total().get("UnblendedCost").amount());
                costs.add(cost);
            }

        } catch (Exception e) {
            logger.error("Could not fetch historical cost for account {}. Service: {}, Region: {}, Days: {}",
                    accountId, serviceName, regionName, days, e);
        }

        HistoricalCostDto result = new HistoricalCostDto(labels, costs);
        dbCache.put(cacheKey, result); // Save to database cache
        return CompletableFuture.completedFuture(result);
    }


    @Async("awsTaskExecutor")
    public CompletableFuture<HistoricalCostDto> getHistoricalCostForDimension(String accountId, String groupBy, String dimensionValue, String tagKey, boolean forceRefresh) {
        String cacheKey = "historicalCostForDimension-" + accountId + "-" + groupBy + "-" + dimensionValue + "-" + tagKey;
        if (!forceRefresh) {
            Optional<HistoricalCostDto> cachedData = dbCache.get(cacheKey, HistoricalCostDto.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }
        
        logger.info("Fetching historical cost for account {}, dimension: {}, value: {}", accountId, groupBy, dimensionValue);
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

        List<String> labels = new ArrayList<>();
        List<Double> costs = new ArrayList<>();

        try {
            LocalDate today = LocalDate.now();
            LocalDate sixMonthsAgo = today.minusMonths(6).withDayOfMonth(1);

            DimensionValues dimension = DimensionValues.builder()
                    .key("TAG".equalsIgnoreCase(groupBy) ? tagKey : groupBy)
                    .values(dimensionValue)
                    .build();
            Expression filter = Expression.builder().dimensions(dimension).build();

            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(sixMonthsAgo.toString()).end(today.plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost")
                    .filter(filter)
                    .build();

            ce.getCostAndUsage(request).resultsByTime().forEach(result -> {
                labels.add(LocalDate.parse(result.timePeriod().start()).format(DateTimeFormatter.ofPattern("MMM yyyy")));
                costs.add(Double.parseDouble(result.total().get("UnblendedCost").amount()));
            });
        } catch (Exception e) {
            logger.error("Could not fetch historical cost for dimension value '{}'", dimensionValue, e);
        }
        
        HistoricalCostDto result = new HistoricalCostDto(labels, costs);
        dbCache.put(cacheKey, result); // Save to database cache
        return CompletableFuture.completedFuture(result);
    }
}