package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertDto {
    private String id;
    private String service;
    private String name;
    private String description;
    private String status;
    private double usage;
    private double limit;
    private String type; 
}