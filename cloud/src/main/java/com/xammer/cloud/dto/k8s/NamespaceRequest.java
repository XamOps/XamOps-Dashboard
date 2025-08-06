package com.xammer.cloud.dto.k8s;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class NamespaceRequest extends KubeConfigRequest {
    private String namespace;
}