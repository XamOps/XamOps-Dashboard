package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for representing a generic GCP resource in a list.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpResourceDto {
    /**
     * The tags associated with the resource (key-value pairs).
     */
    private java.util.Map<String, String> tags;

    public java.util.Map<String, String> getTags() {
        return tags;
    }
    public void setTags(java.util.Map<String, String> tags) {
        this.tags = tags;
    }
    /**
     * The unique identifier of the resource (e.g., instance ID, bucket name).
     */
    private String id;

    /**
     * The user-friendly name of the resource.
     */
    private String name;

    /**
     * The type of GCP service (e.g., "Compute Engine", "Cloud Storage").
     */
    private String type;

    /**
     * The location (region or zone) of the resource.
     */
    private String location;

    /**
     * The current status of the resource (e.g., "RUNNING", "TERMINATED").
     */
    private String status;
}