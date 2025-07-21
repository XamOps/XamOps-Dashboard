package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for carrying historical cost data for charts.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalCostDto {
    private List<String> labels; // e.g., ["Jan 2023", "Feb 2023"]
    private List<Double> costs;
}
