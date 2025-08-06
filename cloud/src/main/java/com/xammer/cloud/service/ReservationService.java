package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.*;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeReservedInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeReservedInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.ModifyReservedInstancesRequest;
import software.amazon.awssdk.services.ec2.model.ModifyReservedInstancesResponse;
import software.amazon.awssdk.services.ec2.model.ReservedInstances;
import software.amazon.awssdk.services.ec2.model.ReservedInstancesConfiguration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReservationService {

    private static final Logger logger = LoggerFactory.getLogger(ReservationService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final CloudListService cloudListService;
    private final List<String> instanceSizeOrder;
    private final String configuredRegion;

    @Autowired
    public ReservationService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            @Lazy CloudListService cloudListService,
            @Value("${rightsizing.instance-size-order}") List<String> instanceSizeOrder) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.cloudListService = cloudListService;
        this.instanceSizeOrder = instanceSizeOrder;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "reservationPageData", key = "#accountId")
    public CompletableFuture<ReservationDto> getReservationPageData(String accountId) {
        CloudAccount account = getAccount(accountId);
        return cloudListService.getRegionStatusForAccount(account).thenCompose(activeRegions -> {
            logger.info("--- LAUNCHING ASYNC DATA FETCH FOR RESERVATION PAGE for account {}---", account.getAwsAccountId());
            CompletableFuture<DashboardData.ReservationAnalysis> analysisFuture = getReservationAnalysis(account);
            CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> purchaseRecsFuture = getReservationPurchaseRecommendations(account);
            CompletableFuture<List<ReservationInventoryDto>> inventoryFuture = getReservationInventory(account, activeRegions);
            CompletableFuture<HistoricalReservationDataDto> historicalDataFuture = getHistoricalReservationData(account);
            CompletableFuture<List<ReservationModificationRecommendationDto>> modificationRecsFuture = getReservationModificationRecommendations(account, activeRegions);

            return CompletableFuture.allOf(analysisFuture, purchaseRecsFuture, inventoryFuture, historicalDataFuture, modificationRecsFuture).thenApply(v -> {
                logger.info("--- RESERVATION PAGE DATA FETCH COMPLETE, COMBINING NOW ---");
                DashboardData.ReservationAnalysis analysis = analysisFuture.join();
                List<DashboardData.ReservationPurchaseRecommendation> recommendations = purchaseRecsFuture.join();
                List<ReservationInventoryDto> inventory = inventoryFuture.join();
                HistoricalReservationDataDto historicalData = historicalDataFuture.join();
                List<ReservationModificationRecommendationDto> modificationRecs = modificationRecsFuture.join();
                return new ReservationDto(analysis, recommendations, inventory, historicalData, modificationRecs);
            });
        });
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "reservationAnalysis", key = "#account.awsAccountId")
    public CompletableFuture<DashboardData.ReservationAnalysis> getReservationAnalysis(CloudAccount account) {
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching reservation analysis for account {}...", account.getAwsAccountId());
        try {
            String today = LocalDate.now().toString();
            String thirtyDaysAgo = LocalDate.now().minusDays(30).toString();
            DateInterval last30Days = DateInterval.builder().start(thirtyDaysAgo).end(today).build();
            GetReservationUtilizationRequest utilRequest = GetReservationUtilizationRequest.builder().timePeriod(last30Days).build();
            List<UtilizationByTime> utilizations = ce.getReservationUtilization(utilRequest).utilizationsByTime();
            GetReservationCoverageRequest covRequest = GetReservationCoverageRequest.builder().timePeriod(last30Days).build();
            List<CoverageByTime> coverages = ce.getReservationCoverage(covRequest).coveragesByTime();
            double utilizationPercentage = utilizations.isEmpty() || utilizations.get(0).total() == null ? 0.0 : Double.parseDouble(utilizations.get(0).total().utilizationPercentage());
            double coveragePercentage = coverages.isEmpty() || coverages.get(0).total() == null ? 0.0 : Double.parseDouble(coverages.get(0).total().coverageHours().coverageHoursPercentage());
            return CompletableFuture.completedFuture(new DashboardData.ReservationAnalysis(
                    utilizationPercentage,
                    coveragePercentage
            ));
        } catch (Exception e) {
            logger.error("Could not fetch reservation analysis data for account {}", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(new DashboardData.ReservationAnalysis(0.0, 0.0));
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "reservationPurchaseRecs", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> getReservationPurchaseRecommendations(CloudAccount account) {
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching RI purchase recommendations for account {}...", account.getAwsAccountId());
        try {
            GetReservationPurchaseRecommendationRequest request = GetReservationPurchaseRecommendationRequest.builder()
                    .lookbackPeriodInDays(LookbackPeriodInDays.SIXTY_DAYS).service("Amazon Elastic Compute Cloud - Compute").build();
            GetReservationPurchaseRecommendationResponse response = ce.getReservationPurchaseRecommendation(request);

            return CompletableFuture.completedFuture(response.recommendations().stream()
                .filter(rec -> rec.recommendationDetails() != null && !rec.recommendationDetails().isEmpty())
                .flatMap(rec -> rec.recommendationDetails().stream()
                    .map(details -> {
                        try {
                            // CORRECTED MAPPING LOGIC
                            return new DashboardData.ReservationPurchaseRecommendation(
                                details.instanceDetails().ec2InstanceDetails().family(),
                                String.valueOf(details.recommendedNumberOfInstancesToPurchase()),
                                String.valueOf(details.recommendedNormalizedUnitsToPurchase()),
                                String.valueOf(details.minimumNumberOfInstancesUsedPerHour()),
                                String.valueOf(details.estimatedMonthlySavingsAmount()),
                                String.valueOf(details.estimatedMonthlyOnDemandCost()),
                                String.valueOf(details.upfrontCost()),
                                String.valueOf(rec.termInYears())
                            );
                        } catch (Exception e) {
                            logger.warn("Failed to process recommendation detail: {}", e.getMessage());
                            return null;
                        }
                    }))
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch reservation purchase recommendations.", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "reservationInventory", key = "#account.awsAccountId")
    public CompletableFuture<List<ReservationInventoryDto>> getReservationInventory(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            Filter activeFilter = Filter.builder().name("state").values("active").build();
            DescribeReservedInstancesRequest request = DescribeReservedInstancesRequest.builder()
                    .filters(activeFilter)
                    .build();
            return ec2.describeReservedInstances(request).reservedInstances().stream()
                    .map(ri -> new ReservationInventoryDto(
                            ri.reservedInstancesId(),
                            ri.offeringTypeAsString(),
                            ri.instanceTypeAsString(),
                            ri.scopeAsString(),
                            ri.availabilityZone(),
                            ri.duration(),
                            ri.start(),
                            ri.end(),
                            ri.instanceCount(),
                            ri.stateAsString()
                    ))
                    .collect(Collectors.toList());
        }, "Reservation Inventory");
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "historicalReservationData", key = "#account.awsAccountId")
    public CompletableFuture<HistoricalReservationDataDto> getHistoricalReservationData(CloudAccount account) {
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching historical reservation data for the last 6 months for account {}...", account.getAwsAccountId());
        try {
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusMonths(6).withDayOfMonth(1);
            DateInterval period = DateInterval.builder()
                    .start(startDate.toString())
                    .end(endDate.toString())
                    .build();

            GetReservationUtilizationRequest utilRequest = GetReservationUtilizationRequest.builder()
                    .timePeriod(period)
                    .granularity(Granularity.MONTHLY)
                    .build();
            List<UtilizationByTime> utilizations = ce.getReservationUtilization(utilRequest).utilizationsByTime();

            GetReservationCoverageRequest covRequest = GetReservationCoverageRequest.builder()
                    .timePeriod(period)
                    .granularity(Granularity.MONTHLY)
                    .build();
            List<CoverageByTime> coverages = ce.getReservationCoverage(covRequest).coveragesByTime();

            List<String> labels = utilizations.stream()
                    .map(u -> LocalDate.parse(u.timePeriod().start()).format(DateTimeFormatter.ofPattern("MMM uuuu")))
                    .collect(Collectors.toList());

            List<Double> utilPercentages = utilizations.stream()
                    .map(u -> Double.parseDouble(u.total().utilizationPercentage()))
                    .collect(Collectors.toList());

            List<Double> covPercentages = coverages.stream()
                    .map(c -> Double.parseDouble(c.total().coverageHours().coverageHoursPercentage()))
                    .collect(Collectors.toList());

            return CompletableFuture.completedFuture(new HistoricalReservationDataDto(labels, utilPercentages, covPercentages));

        } catch (Exception e) {
            logger.error("Could not fetch historical reservation data for account {}", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(new HistoricalReservationDataDto(Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "reservationModificationRecs", key = "#account.awsAccountId")
    public CompletableFuture<List<ReservationModificationRecommendationDto>> getReservationModificationRecommendations(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        logger.info("Fetching reservation modification recommendations for account {}...", account.getAwsAccountId());

        return getReservationInventory(account, activeRegions).thenCompose(activeReservations -> {
            if (activeReservations.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            Map<String, ReservationInventoryDto> activeReservationsMap = activeReservations.stream()
                    .collect(Collectors.toMap(ReservationInventoryDto::getReservationId, Function.identity()));

            CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
            DateInterval last30Days = DateInterval.builder()
                    .start(LocalDate.now().minusDays(30).toString())
                    .end(LocalDate.now().toString())
                    .build();

            GroupDefinition groupByRiId = GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("RESERVATION_ID").build();

            GetReservationUtilizationRequest utilRequest = GetReservationUtilizationRequest.builder()
                    .timePeriod(last30Days)
                    .groupBy(groupByRiId)
                    .build();

            try {
                List<ReservationUtilizationGroup> utilizationGroups = ce.getReservationUtilization(utilRequest).utilizationsByTime().get(0).groups();
                List<ReservationModificationRecommendationDto> recommendations = new ArrayList<>();
                for (ReservationUtilizationGroup group : utilizationGroups) {
                    String reservationId = group.attributes().get("reservationId");
                    double utilizationPercentage = Double.parseDouble(group.utilization().utilizationPercentage());

                    if (utilizationPercentage < 80.0 && activeReservationsMap.containsKey(reservationId)) {
                        ReservationInventoryDto ri = activeReservationsMap.get(reservationId);
                        if ("Convertible".equalsIgnoreCase(ri.getOfferingType())) {
                            String currentType = ri.getInstanceType();
                            String recommendedType = suggestSmallerInstanceType(currentType);
                            if (recommendedType != null && !recommendedType.equals(currentType)) {
                                recommendations.add(new ReservationModificationRecommendationDto(
                                        ri.getReservationId(), currentType, recommendedType,
                                        String.format("Low Utilization (%.1f%%)", utilizationPercentage),
                                        50.0 // Placeholder savings
                                ));
                            }
                        }
                    }
                }
                return CompletableFuture.completedFuture(recommendations);
            } catch (Exception e) {
                logger.error("Could not generate reservation modification recommendations for account {}", account.getAwsAccountId(), e);
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
        });
    }

    public String applyReservationModification(String accountId, ReservationModificationRequestDto request) {
        CloudAccount account = getAccount(accountId);
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, configuredRegion);
        logger.info("Attempting to modify reservation {} for account {}", request.getReservationId(), accountId);

        DescribeReservedInstancesResponse riResponse = ec2.describeReservedInstances(r -> r.reservedInstancesIds(request.getReservationId()));
        if (riResponse.reservedInstances().isEmpty()) {
            throw new IllegalArgumentException("Reservation ID not found: " + request.getReservationId());
        }
        ReservedInstances originalRi = riResponse.reservedInstances().get(0);

        if (!"Convertible".equalsIgnoreCase(originalRi.offeringTypeAsString())) {
            throw new IllegalArgumentException("Cannot modify a non-convertible reservation.");
        }

        ReservedInstancesConfiguration targetConfig = ReservedInstancesConfiguration.builder()
                .instanceType(request.getTargetInstanceType())
                .instanceCount(request.getInstanceCount())
                .platform(originalRi.productDescriptionAsString())
                .availabilityZone(originalRi.availabilityZone())
                .build();

        ModifyReservedInstancesRequest modifyRequest = ModifyReservedInstancesRequest.builder()
                .clientToken(UUID.randomUUID().toString())
                .reservedInstancesIds(request.getReservationId())
                .targetConfigurations(targetConfig)
                .build();

        try {
            ModifyReservedInstancesResponse modifyResponse = ec2.modifyReservedInstances(modifyRequest);
            logger.info("Successfully submitted modification request for RI {}. Transaction ID: {}", request.getReservationId(), modifyResponse.reservedInstancesModificationId());
            return modifyResponse.reservedInstancesModificationId();
        } catch (Exception e) {
            logger.error("Failed to execute RI modification for ID {}: {}", request.getReservationId(), e.getMessage());
            throw new RuntimeException("AWS API call to modify reservation failed.", e);
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "reservationCostByTag", key = "#accountId + '-' + #tagKey")
    public CompletableFuture<List<CostByTagDto>> getReservationCostByTag(String accountId, String tagKey) {
        CloudAccount account = getAccount(accountId);
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching reservation cost by tag: {} for account {}", tagKey, accountId);
        if (tagKey == null || tagKey.isBlank()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        try {
            LocalDate start = LocalDate.now().withDayOfMonth(1);
            LocalDate end = LocalDate.now().plusMonths(1).withDayOfMonth(1);
            DateInterval period = DateInterval.builder().start(start.toString()).end(end.toString()).build();

            Expression filter = Expression.builder().dimensions(DimensionValues.builder()
                    .key(Dimension.PURCHASE_TYPE)
                    .values("Reserved Instances")
                    .build()).build();

            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(period)
                    .granularity(Granularity.MONTHLY)
                    .metrics("AmortizedCost")
                    .filter(filter)
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.TAG).key(tagKey).build())
                    .build();

            List<ResultByTime> results = ce.getCostAndUsage(request).resultsByTime();

            return CompletableFuture.completedFuture(
                results.stream()
                    .flatMap(r -> r.groups().stream())
                    .map(g -> {
                        String tagValue = g.keys().isEmpty() || g.keys().get(0).isEmpty() ? "Untagged" : g.keys().get(0);
                        double cost = Double.parseDouble(g.metrics().get("AmortizedCost").amount());
                        return new CostByTagDto(tagValue, cost);
                    })
                    .filter(dto -> dto.getCost() > 0.01)
                    .collect(Collectors.toList())
            );
        } catch (Exception e) {
            logger.error("Could not fetch reservation cost by tag key '{}' for account {}", tagKey, e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    private String suggestSmallerInstanceType(String instanceType) {
        String[] parts = instanceType.split("\\.");
        if (parts.length != 2) return null;
        String family = parts[0];
        String size = parts[1];

        int currentIndex = this.instanceSizeOrder.indexOf(size);
        if (currentIndex > 0) {
            return family + "." + this.instanceSizeOrder.get(currentIndex - 1);
        }
        return null;
    }

    private <T> CompletableFuture<List<T>> fetchAllRegionalResources(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, Function<String, List<T>> fetchFunction, String serviceName) {
        List<CompletableFuture<List<T>>> futures = activeRegions.stream()
            .map(regionStatus -> CompletableFuture.supplyAsync(() -> {
                try {
                    return fetchFunction.apply(regionStatus.getRegionId());
                } catch (AwsServiceException e) {
                    logger.warn("Reservation task failed for account {}: {} in region {}. AWS Error: {}", account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e.awsErrorDetails().errorMessage());
                    return Collections.<T>emptyList();
                } catch (Exception e) {
                    logger.error("Reservation task failed for account {}: {} in region {}.", account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e);
                    return Collections.<T>emptyList();
                }
            }))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList()));
    }
}