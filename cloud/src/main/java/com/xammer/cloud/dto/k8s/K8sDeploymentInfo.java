package com.xammer.cloud.dto.k8s;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class K8sDeploymentInfo {
    private String name;
    private String ready;
    private int upToDate;
    private int available;
    private String age;
}