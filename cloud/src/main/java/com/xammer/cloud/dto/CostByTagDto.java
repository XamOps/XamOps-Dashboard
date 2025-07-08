package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for representing cost data grouped by a specific tag value.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CostByTagDto {
    private String tagValue;
    private double cost;
}
