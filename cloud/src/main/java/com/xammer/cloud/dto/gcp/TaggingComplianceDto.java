package com.xammer.cloud.dto.gcp;

import lombok.Data;
import java.util.List;

@Data
public class TaggingComplianceDto {
    private double compliancePercentage;
    private int totalResourcesScanned;
    private int untaggedResourcesCount;
    private List<UntaggedResource> untaggedResources;

    @Data
    public static class UntaggedResource {
        private String resourceId;
        private String resourceType;

        public UntaggedResource(String resourceId, String resourceType) {
            this.resourceId = resourceId;
            this.resourceType = resourceType;
        }
    }
}