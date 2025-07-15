package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinition;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinitionType;
import software.amazon.awssdk.services.costexplorer.model.Granularity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class CostService {

    private static final Logger logger = LoggerFactory.getLogger(CostService.class);
    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;

    public CostService(AwsClientProvider awsClientProvider, CloudAccountRepository cloudAccountRepository) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<CostDto>> getCostBreakdown(String accountId, String groupBy, String tagKey) {
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

            return CompletableFuture.completedFuture(costExplorerClient.getCostAndUsage(request).resultsByTime()
                    .stream().flatMap(r -> r.groups().stream())
                    .map(g -> new CostDto(g.keys().get(0),
                            Double.parseDouble(g.metrics().get("UnblendedCost").amount())))
                    .filter(s -> s.getAmount() > 0.01)
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch cost breakdown for account {}.", accountId, e);
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
    }
}
