package com.xammer.cloud.dto.k8s;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class K8sPodInfo {
    private String name;
    private String ready;
    private String status;
    private int restarts;
    private String age;
    private String nodeName;
}
