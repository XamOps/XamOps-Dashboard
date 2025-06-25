package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.io.IOException;

@Service
public class PricingService {

    private static final Logger logger = LoggerFactory.getLogger(PricingService.class);
    private final PricingClient pricingClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PricingService(PricingClient pricingClient) {
        this.pricingClient = pricingClient;
    }

    /**
     * Fetches the monthly cost per GB for a specific EBS volume type in a given region.
     * The result is cached for 24 hours to avoid repeated API calls.
     * @param region e.g., "us-east-1"
     * @param volumeType e.g., "gp3"
     * @return The cost per GB/month, or a default value if not found.
     */
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
                logger.warn("No price list found for EBS volume type {} in region {}", volumeType, region);
                return 0.10; // Default fallback price
            }

            JsonNode priceList = objectMapper.readTree(response.priceList().get(0));
            JsonNode terms = priceList.path("terms").path("OnDemand");
            if (terms.isMissingNode() || terms.isEmpty()) {
                 return 0.10;
            }

            JsonNode priceDimensions = terms.elements().next().path("priceDimensions");
             if (priceDimensions.isMissingNode() || priceDimensions.isEmpty()) {
                 return 0.10;
            }

            JsonNode pricePerUnit = priceDimensions.elements().next().path("pricePerUnit").path("USD");
            return pricePerUnit.asDouble(0.10);

        } catch (IOException e) {
            logger.error("Failed to parse pricing JSON for EBS", e);
            return 0.10; // Default fallback price
        } catch (Exception e) {
            logger.error("Failed to fetch price for EBS volume type {} in region {}", volumeType, region, e);
            return 0.10; // Default fallback price
        }
    }
}
