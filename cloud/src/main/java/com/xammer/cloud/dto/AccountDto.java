package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountDto {
    private Long dbId;
    private String name;
    private String id; // AWS Account ID
    private String access;
    private String connection;
    private String status;
    private String roleArn;
    private String externalId; // ADDED: To pass the externalId to the frontend
}
