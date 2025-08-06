package com.xammer.cloud.dto.k8s;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ClusterUsageDto {
    private double cpuTotal;          // cores
    private double cpuUsage;          // cores
    private double cpuRequests;       // cores
    private double cpuLimits;         // cores
    private double memoryTotal;       // MiB
    private double memoryUsage;       // MiB
    private double memoryRequests;    // MiB
    private double memoryLimits;      // MiB
    private int nodeCount;
    private int podCount;
}