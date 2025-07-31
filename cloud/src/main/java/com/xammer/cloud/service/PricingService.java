package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount; // ADDED
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.SpotPrice;
import software.amazon.awssdk.services.ec2.model.InstanceType; // ADDED
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;
import software.amazon.awssdk.services.rds.model.DBInstance;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator; // ADDED
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

@Service
public class PricingService {

    private static final Logger logger = LoggerFactory.getLogger(PricingService.class);
    private final PricingClient pricingClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AwsClientProvider awsClientProvider; // CHANGED

    @Value("${pricing.ebs.fallback-price:0.10}")
    private double ebsFallbackPrice;

    // CHANGED: Injected AwsClientProvider instead of a generic Ec2Client
    public PricingService(PricingClient pricingClient, AwsClientProvider awsClientProvider) {
        this.pricingClient = pricingClient;
        this.awsClientProvider = awsClientProvider;
    }

    @Cacheable(value = "spotPrice", key = "#account.awsAccountId + '-' + #region + '-' + #instanceType")
public double getSpotInstancePrice(String instanceType, String region, CloudAccount account) {
    logger.info("Fetching spot price for instance type: {} in region: {}", instanceType, region);
    try {
        // Use the provider to get a correctly configured, region-specific client
        Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);

        DescribeSpotPriceHistoryRequest request = DescribeSpotPriceHistoryRequest.builder()
            .instanceTypes(InstanceType.fromValue(instanceType))
            .productDescriptions("Linux/UNIX")
            .startTime(Instant.now().minusSeconds(3600))
            .endTime(Instant.now())
            .build();

        // Find the most recent spot price from the history
        Optional<SpotPrice> spotPrice = ec2Client.describeSpotPriceHistory(request).spotPriceHistory().stream()
            .max(Comparator.comparing(SpotPrice::timestamp));

        if (spotPrice.isPresent()) {
            return Double.parseDouble(spotPrice.get().spotPrice());
        }
    } catch (Exception e) {
        logger.error("Failed to fetch spot price for instance type {} in region {}", instanceType, region, e);
    }
    return 0.0;
}


    @Cacheable(value = "ebsPrice", key = "#region + '-' + #volumeType")
    public double getEbsGbMonthPrice(String region, String volumeType) {
        logger.info("Fetching live EBS price for region: {}, type: {}", region, volumeType);
        Filter regionFilter = Filter.builder().field("regionCode").value(region).type("TERM_MATCH").build();
        Filter volumeApiNameFilter = Filter.builder().field("volumeApiName").value(volumeType).type("TERM_MATCH").build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                .filters(regionFilter, volumeApiNameFilter)
                .build();

        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (response.priceList().isEmpty()) {
                logger.warn("No price list found for EBS volume type {} in region {}. Using fallback price.", volumeType, region);
                return ebsFallbackPrice;
            }

            JsonNode priceList = objectMapper.readTree(response.priceList().get(0));
            JsonNode terms = priceList.path("terms").path("OnDemand");
            if (terms.isMissingNode() || terms.isEmpty()) {
                return ebsFallbackPrice;
            }

            JsonNode priceDimensions = terms.elements().next().path("priceDimensions");
            if (priceDimensions.isMissingNode() || priceDimensions.isEmpty()) {
                return ebsFallbackPrice;
            }

            JsonNode pricePerUnit = priceDimensions.elements().next().path("pricePerUnit").path("USD");
            return pricePerUnit.asDouble(ebsFallbackPrice);

        } catch (IOException e) {
            logger.error("Failed to parse pricing JSON for EBS", e);
            return ebsFallbackPrice;
        } catch (Exception e) {
            logger.error("Failed to fetch price for EBS volume type {} in region {}", volumeType, region, e);
            return ebsFallbackPrice;
        }
    }

    @Cacheable(value = "ec2Price", key = "#region + '-' + #instanceType")
    public double getEc2InstanceMonthlyPrice(String instanceType, String region) {
        logger.info("Fetching live EC2 price for region: {}, type: {}", region, instanceType);
        Filter regionFilter = Filter.builder().field("location").value(getRegionDescription(region)).type("TERM_MATCH").build();
        Filter instanceTypeFilter = Filter.builder().field("instanceType").value(instanceType).type("TERM_MATCH").build();
        Filter tenancyFilter = Filter.builder().field("tenancy").value("Shared").type("TERM_MATCH").build();
        Filter osFilter = Filter.builder().field("operatingSystem").value("Linux").type("TERM_MATCH").build();
        Filter capacityFilter = Filter.builder().field("capacitystatus").value("Used").type("TERM_MATCH").build();
        Filter preinstalledSwFilter = Filter.builder().field("preInstalledSw").value("NA").type("TERM_MATCH").build();


        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                .filters(regionFilter, instanceTypeFilter, tenancyFilter, osFilter, capacityFilter, preinstalledSwFilter)
                .build();

        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (response.priceList().isEmpty()) {
                logger.warn("No price list found for EC2 instance type {} in region {}", instanceType, region);
                return 0.0;
            }

            JsonNode priceList = objectMapper.readTree(response.priceList().get(0));
            JsonNode onDemandTerms = priceList.path("terms").path("OnDemand");

            Iterator<JsonNode> elements = onDemandTerms.elements();
            if (elements.hasNext()) {
                JsonNode priceDimensions = elements.next().path("priceDimensions");
                Iterator<JsonNode> dimensions = priceDimensions.elements();
                if(dimensions.hasNext()) {
                    JsonNode pricePerUnit = dimensions.next().path("pricePerUnit").path("USD");
                    double hourlyPrice = pricePerUnit.asDouble(0.0);
                    return hourlyPrice * 24 * 30; // Estimate monthly cost
                }
            }
        } catch (Exception e) {
            logger.error("Failed to fetch or parse price for EC2 instance type {} in region {}", instanceType, region, e);
        }
        return 0.0; // Fallback
    }

    @Cacheable(value = "elasticIpPrice", key = "#region")
    public double getElasticIpMonthlyPrice(String region) {
        logger.info("Fetching live Elastic IP price for region: {}", region);
        Filter regionFilter = Filter.builder().field("location").value(getRegionDescription(region)).type("TERM_MATCH").build();
        Filter groupFilter = Filter.builder().field("group").value("Elastic IP Address").type("TERM_MATCH").build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                .filters(regionFilter, groupFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (response.priceList().isEmpty()) {
                logger.warn("No price list found for Elastic IP in region {}", region);
                return 5.0; // Fallback
            }
            JsonNode priceList = objectMapper.readTree(response.priceList().get(0));
            JsonNode onDemand = priceList.path("terms").path("OnDemand");
            JsonNode priceDimensions = onDemand.elements().next().path("priceDimensions");
            JsonNode pricePerUnit = priceDimensions.elements().next().path("pricePerUnit").path("USD");
            double hourlyPrice = pricePerUnit.asDouble(0.005);
            return hourlyPrice * 24 * 30;
        } catch (Exception e) {
            logger.error("Failed to fetch price for Elastic IP in region {}", region, e);
            return 5.0; // Fallback
        }
    }

    @Cacheable(value = "rdsPrice", key = "#region + '-' + #dbInstance.dbInstanceClass()")
    public double getRdsInstanceMonthlyPrice(DBInstance dbInstance, String region) {
        logger.info("Fetching live RDS price for region: {}, type: {}", region, dbInstance.dbInstanceClass());
        Filter regionFilter = Filter.builder().field("location").value(getRegionDescription(region)).type("TERM_MATCH").build();
        Filter instanceClassFilter = Filter.builder().field("instanceType").value(dbInstance.dbInstanceClass()).type("TERM_MATCH").build();
        Filter engineFilter = Filter.builder().field("databaseEngine").value(dbInstance.engine()).type("TERM_MATCH").build();

        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonRDS")
                .filters(regionFilter, instanceClassFilter, engineFilter)
                .build();
        try {
            GetProductsResponse response = pricingClient.getProducts(request);
            if (response.priceList().isEmpty()) {
                logger.warn("No price list found for RDS instance type {} in region {}", dbInstance.dbInstanceClass(), region);
                return 20.0; // Fallback
            }
            JsonNode priceList = objectMapper.readTree(response.priceList().get(0));
            JsonNode onDemand = priceList.path("terms").path("OnDemand");
            JsonNode priceDimensions = onDemand.elements().next().path("priceDimensions");
            JsonNode pricePerUnit = priceDimensions.elements().next().path("pricePerUnit").path("USD");
            double hourlyPrice = pricePerUnit.asDouble(0.02);
            return hourlyPrice * 24 * 30;
        } catch (Exception e) {
            logger.error("Failed to fetch price for RDS instance {} in region {}", dbInstance.dbInstanceIdentifier(), region, e);
            return 20.0; // Fallback
        }
    }

    private String getRegionDescription(String regionCode) {
        Map<String, String> regionMap = Map.ofEntries(
            Map.entry("us-east-1", "US East (N. Virginia)"),
            Map.entry("us-east-2", "US East (Ohio)"),
            Map.entry("us-west-1", "US West (N. California)"),
            Map.entry("us-west-2", "US West (Oregon)"),
            Map.entry("ap-south-1", "Asia Pacific (Mumbai)"),
            Map.entry("ap-northeast-2", "Asia Pacific (Seoul)"),
            Map.entry("ap-southeast-1", "Asia Pacific (Singapore)"),
            Map.entry("ap-southeast-2", "Asia Pacific (Sydney)"),
            Map.entry("ap-northeast-1", "Asia Pacific (Tokyo)"),
            Map.entry("ca-central-1", "Canada (Central)"),
            Map.entry("eu-central-1", "EU (Frankfurt)"),
            Map.entry("eu-west-1", "EU (Ireland)"),
            Map.entry("eu-west-2", "EU (London)"),
            Map.entry("eu-west-3", "EU (Paris)"),
            Map.entry("eu-north-1", "EU (Stockholm)"),
            Map.entry("sa-east-1", "South America (Sao Paulo)")
        );
        return regionMap.getOrDefault(regionCode, regionCode);
    }
}
