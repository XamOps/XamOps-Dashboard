package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for representing a single GCP security finding.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpSecurityFinding {
    /**
     * The category of the finding.
     * e.g., "IAM", "Networking", "Storage"
     */
    private String category;

    /**
     * A detailed description of the security finding.
     */
    private String description;

    /**
     * The severity level of the finding.
     * e.g., "HIGH", "MEDIUM", "LOW"
     */
    private String severity;

    /**
     * The full name of the resource associated with the finding.
     */
    private String resourceName;
}
