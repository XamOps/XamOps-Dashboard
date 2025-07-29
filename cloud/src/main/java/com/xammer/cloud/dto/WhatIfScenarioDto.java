package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WhatIfScenarioDto {
    private String resourceId;
    private String currentInstanceType;
    private String targetInstanceType;
    private double currentMonthlyCost;
    private double targetMonthlyCost;
    private double costDifference;
    private double currentPeakCpu;
    private double projectedPeakCpu;
    private String performanceChangeSummary;
}