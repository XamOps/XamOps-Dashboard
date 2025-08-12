package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for representing a single GCP rightsizing recommendation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpOptimizationRecommendation {
    /**
     * The name of the resource that can be rightsized.
     * e.g., "gce-instance-1"
     */
    private String resourceName;

    /**
     * The current machine type of the instance.
     * e.g., "n1-standard-4"
     */
    private String currentMachineType;

    /**
     * The recommended machine type for the instance.
     * e.g., "n1-standard-2"
     */
    private String recommendedMachineType;

    /**
     * The estimated potential savings per month in USD.
     */
    private double monthlySavings;
}
