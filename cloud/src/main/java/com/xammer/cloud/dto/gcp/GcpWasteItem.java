package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for representing a single wasted GCP resource.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpWasteItem {
    /**
     * The name or identifier of the wasted resource.
     * e.g., "my-idle-disk", "my-unused-ip"
     */
    private String resourceName;

    /**
     * The type of the wasted resource.
     * e.g., "Idle Persistent Disk", "Unused IP Address"
     */
    private String type;

    /**
     * The location (region or zone) of the resource.
     * e.g., "us-central1", "europe-west1-b"
     */
    private String location;

    /**
     * The estimated potential savings per month in USD.
     */
    private double monthlySavings;
}
