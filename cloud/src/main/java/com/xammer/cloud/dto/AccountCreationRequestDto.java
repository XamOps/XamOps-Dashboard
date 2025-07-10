package com.xammer.cloud.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AccountCreationRequestDto {
    private String accountName;
    private String accessType; // "read-only" or "read-write"
}