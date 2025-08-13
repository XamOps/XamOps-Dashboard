package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CloudAccountRepository extends JpaRepository<CloudAccount, Long> {

    Optional<CloudAccount> findByGcpProjectId(String gcpProjectId);
    Optional<CloudAccount> findByExternalId(String externalId);

    Optional<CloudAccount> findByAwsAccountId(String awsAccountId);

    List<CloudAccount> findByClientId(Long clientId);

    Optional<CloudAccount> findByAwsAccountIdAndClientId(String awsAccountId, Long clientId);

    Optional<CloudAccount> findByAwsAccountIdOrGcpProjectId(String awsAccountId, String gcpProjectId);
}