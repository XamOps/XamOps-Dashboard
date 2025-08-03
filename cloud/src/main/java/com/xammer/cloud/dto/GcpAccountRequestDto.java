package com.xammer.cloud.dto;

import lombok.Data;

@Data
public class GcpAccountRequestDto {
    private String accountName;
    private String projectId;
    private String serviceAccountKey;
}