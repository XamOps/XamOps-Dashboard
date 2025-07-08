package com.xammer.cloud.dto.k8s;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class K8sEventInfo {
    private String lastSeen;
    private String type;
    private String reason;
    private String object;
    private String message;
}
