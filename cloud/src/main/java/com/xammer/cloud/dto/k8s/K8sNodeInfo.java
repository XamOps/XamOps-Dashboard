package com.xammer.cloud.dto.k8s;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private String cpuUsage;  // Changed from Map to String
    private String memUsage;  // Changed from Map to String
}