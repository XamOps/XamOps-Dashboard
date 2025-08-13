package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpMetricDto {
    private String timestamp;
    private double value;
}