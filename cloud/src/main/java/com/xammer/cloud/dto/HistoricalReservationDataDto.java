package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for holding historical reservation utilization and coverage data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoricalReservationDataDto {
    private List<String> labels; // e.g., ["Jan 2023", "Feb 2023", "Mar 2023"]
    private List<Double> utilizationPercentages;
    private List<Double> coveragePercentages;
}
