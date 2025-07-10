package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountDto {
    private String name;
    private String id;
    private String access;
    private String connection;
    private String status;
    private String roleArn; // To store after creation
}