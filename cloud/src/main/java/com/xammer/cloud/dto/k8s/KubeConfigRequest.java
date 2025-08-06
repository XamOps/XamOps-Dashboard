package com.xammer.cloud.dto.k8s;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class KubeConfigRequest {
    private String kubeConfigYaml;
}