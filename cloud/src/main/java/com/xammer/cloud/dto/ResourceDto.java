package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceDto {
    private String id;
    private String name;
    private String type;
    private String region;
    private String state;
    private Instant launchTime;
    private Map<String, String> details; // For type-specific info like instance type, volume size etc.
}
