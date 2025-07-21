package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
public class PerformanceInsightsService {

    private static final Logger logger = LoggerFactory.getLogger(PerformanceInsightsService.class);

    @Autowired
    private AwsDataService awsDataService;
    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    private final Map<String, PerformanceInsightDto> archivedInsights = new HashMap<>();

    public List<PerformanceInsightDto> getInsights(String accountId, String severity) {
        logger.info("Starting multi-region performance insights scan for account: {}", accountId);
        List<PerformanceInsightDto> allInsights = new ArrayList<>();
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        try {
            // Fetch all active regions for the account first.
            List<DashboardData.RegionStatus> activeRegions = awsDataService.getRegionStatusForAccount(account).get();
            logger.info("Found {} active regions to scan for performance insights.", activeRegions.size());

            // Use CompletableFuture to scan all regions in parallel.
            List<CompletableFuture<List<PerformanceInsightDto>>> futures = new ArrayList<>();
            for (DashboardData.RegionStatus region : activeRegions) {
                CompletableFuture<List<PerformanceInsightDto>> regionFuture = CompletableFuture.supplyAsync(() -> {
                    List<PerformanceInsightDto> regionalInsights = new ArrayList<>();
                    regionalInsights.addAll(getEC2InsightsForRegion(account, region.getRegionId()));
                    regionalInsights.addAll(getRDSInsightsForRegion(account, region.getRegionId()));
                    regionalInsights.addAll(getLambdaInsightsForRegion(account, region.getRegionId()));
                    return regionalInsights;
                });
                futures.add(regionFuture);
            }
            
            // S3 insights are global but need a client, so we handle them separately.
            futures.add(CompletableFuture.supplyAsync(() -> getS3Insights(account)));

            // Wait for all scans to complete and collect the results.
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            for (CompletableFuture<List<PerformanceInsightDto>> future : futures) {
                allInsights.addAll(future.get());
            }

            logger.info("Total insights generated across all regions before filtering: {}", allInsights.size());

            // Filter by severity if specified.
            if (severity != null && !severity.isEmpty() && !severity.equalsIgnoreCase("ALL")) {
                PerformanceInsightDto.InsightSeverity severityEnum =
                        PerformanceInsightDto.InsightSeverity.valueOf(severity.toUpperCase());
                return allInsights.stream()
                        .filter(insight -> insight.getSeverity() == severityEnum)
                        .collect(Collectors.toList());
            }

            return allInsights;

        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error fetching performance insights for account: {}", accountId, e);
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        }
    }
    
    private List<PerformanceInsightDto> getEC2InsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for EC2 performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
           // New Code ✅
Ec2Client ec2Client = awsDataService.getEc2Client(account.getAwsAccountId(), regionId);
CloudWatchClient cloudWatchClient = awsDataService.getCloudWatchClient(account.getAwsAccountId(), regionId);
            
            var instancesResponse = ec2Client.describeInstances();
            
            instancesResponse.reservations().forEach(reservation -> {
                reservation.instances().forEach(instance -> {
                    if (instance.state().nameAsString().equals("running")) {
                        double avgCpuUtilization = getAverageMetric(
                            cloudWatchClient, "AWS/EC2", "CPUUtilization",
                            "InstanceId", instance.instanceId()
                        );
                        
                        if (avgCpuUtilization < 10.0) {
                            insights.add(new PerformanceInsightDto(
                                "ec2-" + instance.instanceId() + "-underutilized",
                                "EC2 instance " + instance.instanceId() + " is underutilized with " +
                                String.format("%.1f", avgCpuUtilization) + "% average CPU utilization",
                                "EC2 instance showing low resource utilization",
                                avgCpuUtilization < 5.0 ? PerformanceInsightDto.InsightSeverity.CRITICAL :
                                                         PerformanceInsightDto.InsightSeverity.WARNING,
                                account.getAwsAccountId(), 1, "EC2", instance.instanceId(),
                                "Consider downsizing or terminating this instance", "/docs/ec2-rightsizing",
                                calculateEC2Savings(instance.instanceType().toString()),
                                regionId, Instant.now().toString()
                            ));
                        }
                        
                        String instanceType = instance.instanceType().toString();
                        if (instanceType.startsWith("t2.")) {
                             insights.add(new PerformanceInsightDto(
                                "ec2-" + instance.instanceId() + "-generation",
                                "EC2 instance " + instance.instanceId() + " is using a previous generation instance type (" + instanceType + ").",
                                "An older instance family is in use.",
                                PerformanceInsightDto.InsightSeverity.WEAK_WARNING,
                                account.getAwsAccountId(), 1, "EC2", instance.instanceId(),
                                "Upgrade to a newer instance family (e.g., t3) for better price-performance.", "/docs/ec2-generations",
                                calculateEC2Savings(instanceType) * 0.1,
                                regionId, Instant.now().toString()
                            ));
                        }
                    }
                });
            });
        } catch (Exception e) {
            logger.error("Error fetching EC2 insights for account: {} in region {}", account.getAwsAccountId(), regionId, e);
        }
        return insights;
    }

    private List<PerformanceInsightDto> getRDSInsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for RDS performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            RdsClient rdsClient = awsDataService.awsClientProvider.getRdsClient(account, regionId);
            CloudWatchClient cloudWatchClient = awsDataService.awsClientProvider.getCloudWatchClient(account, regionId);
            
            var dbInstancesResponse = rdsClient.describeDBInstances();
            
            for (DBInstance dbInstance : dbInstancesResponse.dbInstances()) {
                if (dbInstance.dbInstanceStatus().equals("available")) {
                    double avgCpuUtilization = getAverageMetric(
                        cloudWatchClient, "AWS/RDS", "CPUUtilization",
                        "DBInstanceIdentifier", dbInstance.dbInstanceIdentifier()
                    );
                    
                    double avgConnections = getAverageMetric(
                        cloudWatchClient, "AWS/RDS", "DatabaseConnections",
                        "DBInstanceIdentifier", dbInstance.dbInstanceIdentifier()
                    );
                    
                    if (avgCpuUtilization < 20.0 && avgConnections < 5.0) {
                        insights.add(new PerformanceInsightDto(
                            "rds-" + dbInstance.dbInstanceIdentifier() + "-underutilized",
                            "RDS instance " + dbInstance.dbInstanceIdentifier() +
                            " shows low utilization with " + String.format("%.1f", avgCpuUtilization) +
                            "% CPU and " + String.format("%.0f", avgConnections) + " average connections",
                            "RDS instance showing low resource utilization",
                            PerformanceInsightDto.InsightSeverity.WARNING,
                            account.getAwsAccountId(), 1, "RDS", dbInstance.dbInstanceIdentifier(),
                            "Consider downsizing this RDS instance class", "/docs/rds-rightsizing",
                            calculateRDSSavings(dbInstance.dbInstanceClass()),
                            regionId, Instant.now().toString()
                        ));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching RDS insights for account: {} in region {}", account.getAwsAccountId(), regionId, e);
        }
        return insights;
    }
    
    private List<PerformanceInsightDto> getLambdaInsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for Lambda performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            LambdaClient lambdaClient = awsDataService.awsClientProvider.getLambdaClient(account, regionId);
            var functionsResponse = lambdaClient.listFunctions();
            
            for (FunctionConfiguration function : functionsResponse.functions()) {
                var versionsResponse = lambdaClient.listVersionsByFunction(
                    builder -> builder.functionName(function.functionName())
                );
                
                long activeVersions = versionsResponse.versions().stream()
                    .filter(v -> !v.version().equals("$LATEST"))
                    .count();
                
                if (activeVersions > 0) {
                    insights.add(new PerformanceInsightDto(
                        "lambda-" + function.functionName() + "-versions",
                        "Versions of the " + function.functionName() + " lambda function is not used according to the best practices",
                        "Lambda function versions are not optimized for best practices",
                        PerformanceInsightDto.InsightSeverity.WEAK_WARNING,
                        account.getAwsAccountId(), 1, "Lambda", function.functionName(),
                        "Consider using aliases to use this version according to the best practices", "/docs/lambda-best-practices",
                        calculateLambdaSavings(function), regionId, Instant.now().toString()
                    ));
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching Lambda insights for account: {} in region {}", account.getAwsAccountId(), regionId, e);
        }
        return insights;
    }

    private List<PerformanceInsightDto> getS3Insights(CloudAccount account) {
        logger.info("Checking for S3 performance insights...");
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
           // New Code ✅
S3Client s3Client = awsDataService.getS3Client(account.getAwsAccountId()); // getS3Client doesn't need a region
CloudWatchClient cloudWatchClient = awsDataService.getCloudWatchClient(account.getAwsAccountId(), "us-east-1");
            
            var bucketsResponse = s3Client.listBuckets();
            
            for (Bucket bucket : bucketsResponse.buckets()) {
                double bucketSize = getBucketSize(cloudWatchClient, bucket.name());
                
                if (bucketSize > 1000000) { // > 1MB
                    insights.add(new PerformanceInsightDto(
                        "s3-" + bucket.name() + "-storage-class",
                        "Consider moving S3 bucket " + bucket.name() +
                        " objects to the Intelligent-Tiering storage class with S3 Lifecycle rule",
                        "S3 storage class optimization opportunity",
                        PerformanceInsightDto.InsightSeverity.WARNING,
                        account.getAwsAccountId(), 1, "S3", bucket.name(),
                        "Move to Intelligent-Tiering storage class to save up to 40% of current bucket costs", "/docs/s3-intelligent-tiering",
                        calculateS3Savings(bucketSize), "Global", Instant.now().toString()
                    ));
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching S3 insights for account: {}", account.getAwsAccountId(), e);
        }
        return insights;
    }

    private double getAverageMetric(CloudWatchClient cloudWatchClient, String namespace,
                                   String metricName, String dimensionName, String dimensionValue) {
        try {
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(24, ChronoUnit.HOURS);

            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                .namespace(namespace)
                .metricName(metricName)
                .dimensions(Dimension.builder().name(dimensionName).value(dimensionValue).build())
                .startTime(startTime).endTime(endTime).period(86400)
                .statistics(Statistic.AVERAGE).build();

            GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request);

            return response.datapoints().stream()
                .mapToDouble(Datapoint::average).average().orElse(0.0);
        } catch (Exception e) {
            logger.debug("Could not fetch metric {} for {}", metricName, dimensionValue, e);
            return 0.0;
        }
    }
    
    public Map<String, Object> getInsightsSummary(String accountId) {
        List<PerformanceInsightDto> insights = getInsights(accountId, "ALL");
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalInsights", insights.size());
        summary.put("critical", insights.stream().mapToInt(i -> 
            i.getSeverity() == PerformanceInsightDto.InsightSeverity.CRITICAL ? 1 : 0).sum());
        summary.put("warning", insights.stream().mapToInt(i -> 
            i.getSeverity() == PerformanceInsightDto.InsightSeverity.WARNING ? 1 : 0).sum());
        summary.put("weakWarning", insights.stream().mapToInt(i -> 
            i.getSeverity() == PerformanceInsightDto.InsightSeverity.WEAK_WARNING ? 1 : 0).sum());
        summary.put("potentialSavings", insights.stream()
            .mapToDouble(PerformanceInsightDto::getPotentialSavings).sum());
        
        return summary;
    }
    
    private double getBucketSize(CloudWatchClient cloudWatchClient, String bucketName) {
        try {
            Instant endTime = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant startTime = endTime.minus(1, ChronoUnit.DAYS);
            
            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                .namespace("AWS/S3")
                .metricName("BucketSizeBytes")
                .dimensions(
                    Dimension.builder().name("BucketName").value(bucketName).build(),
                    Dimension.builder().name("StorageType").value("StandardStorage").build()
                )
                .startTime(startTime)
                .endTime(endTime)
                .period(86400) // 24 hours
                .statistics(Statistic.AVERAGE)
                .build();
                
            GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request);
            
            return response.datapoints().stream()
                .mapToDouble(Datapoint::average)
                .max()
                .orElse(0.0);
                
        } catch (Exception e) {
            logger.debug("Could not fetch bucket size for {}", bucketName);
            return 0.0;
        }
    }
    private double calculateLambdaSavings(FunctionConfiguration function) {
        int memoryMB = function.memorySize();
        return (memoryMB / 128.0) * 2.5;
    }
    private double calculateS3Savings(double sizeBytes) {
        double sizeGB = sizeBytes / (1024 * 1024 * 1024);
        return sizeGB * 0.023 * 0.4;
    }
    private double calculateEC2Savings(String instanceType) {
        Map<String, Double> costs = Map.of(
            "t2.micro", 8.5, "t2.small", 17.0, "t3.medium", 30.0,
            "t3.large", 60.0, "m5.large", 70.0, "m5.xlarge", 140.0
        );
        return costs.getOrDefault(instanceType, 50.0);
    }
    private double calculateRDSSavings(String instanceClass) {
        Map<String, Double> costs = Map.of(
            "db.t3.micro", 15.0, "db.t3.small", 30.0, "db.t3.medium", 60.0,
            "db.m5.large", 120.0, "db.m5.xlarge", 240.0
        );
        return costs.getOrDefault(instanceClass, 80.0);
    }
    public void archiveInsight(String insightId) {
        archivedInsights.put(insightId, null);
        logger.info("Archived insight: {}", insightId);
    }
    public void bulkArchiveInsights(List<String> insightIds) {
        insightIds.forEach(this::archiveInsight);
        logger.info("Bulk archived {} insights", insightIds.size());
    }
    public void exportInsightsToExcel(String accountId, String severity, HttpServletResponse response) {
        List<PerformanceInsightDto> insights = getInsights(accountId, severity);
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Performance Insights");
            Row headerRow = sheet.createRow(0);
            String[] headers = {"Insight", "Severity", "Account", "Quantity", "Resource Type", "Resource ID", "Region", "Recommendation", "Potential Savings"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(createHeaderStyle(workbook));
            }
            for (int i = 0; i < insights.size(); i++) {
                Row row = sheet.createRow(i + 1);
                PerformanceInsightDto insight = insights.get(i);
                row.createCell(0).setCellValue(insight.getInsight());
                row.createCell(1).setCellValue(insight.getSeverity().toString());
                row.createCell(2).setCellValue(insight.getAccount());
                row.createCell(3).setCellValue(insight.getQuantity());
                row.createCell(4).setCellValue(insight.getResourceType());
                row.createCell(5).setCellValue(insight.getResourceId());
                row.createCell(6).setCellValue(insight.getRegion());
                row.createCell(7).setCellValue(insight.getRecommendation());
                row.createCell(8).setCellValue(insight.getPotentialSavings());
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=performance-insights-" + accountId + "-" + System.currentTimeMillis() + ".xlsx");
            workbook.write(response.getOutputStream());
        } catch (IOException e) {
            logger.error("Error exporting insights to Excel", e);
        }
    }
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
