package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Column;

@Entity
@Data
@NoArgsConstructor
public class CloudAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String accountName;

    @Column(unique = true)
    private String awsAccountId;

    @Column(nullable = false, unique = true)
    private String externalId;

    @Column(nullable = false)
    private String accessType; // "read-only" or "read-write"

    @Column
    private String roleArn;

    @Column(nullable = false)
    private String status = "PENDING"; // PENDING, CONNECTED, FAILED

    public CloudAccount(String accountName, String externalId, String accessType) {
        this.accountName = accountName;
        this.externalId = externalId;
        this.accessType = accessType;
    }
}