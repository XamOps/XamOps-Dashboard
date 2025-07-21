package com.xammer.cloud.dto.k8s;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class K8sNodeInfo {
    private String name;
    private String status;
    private String instanceType;
    private String availabilityZone;
    private String age;
    private String k8sVersion;
    // ADDED: Fields for live performance metrics
    private Map<String, Double> cpuUsage;
    private Map<String, Double> memUsage;
}
