package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpCostDto {
    private String name;
    private double amount;
    private boolean isAnomaly;
}