package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.services.cloudtrail.model.Event;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceDetailDto {
    private String id;
    private String name;
    private String type;
    private String region;
    private String state;
    private Instant launchTime;
    private Map<String, String> details;
    private List<Map.Entry<String, String>> tags;
    private Map<String, List<MetricDto>> metrics;
    private List<CloudTrailEventDto> events;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CloudTrailEventDto {
        private String eventId;
        private String eventName;
        private Instant eventTime;
        private String username;
        private String sourceIpAddress;
        private boolean readOnly;
    }
}
